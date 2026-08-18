# XVF execution data and API requirements

**Status:** architecture review artifact; not yet an implementation specification  
**Reviewed:** 2026-08-17  
**Canonical strategy documents:** `XVF_STRATEGY.md` and `XVF_IMPLEMENTATION.md`

This document isolates the data and venue APIs required to execute XVF safely so that the design can
be reviewed independently. API details are time-sensitive and must be checked again against official
venue documentation before enabling live orders.

## 1. Scope

The existing funding pipeline is an input to signal generation:

```text
Lambda -> DynamoDB xvf-funding-observation
       -> scheduled export
       -> PostgreSQL venue_funding_observation
       -> XVF signal and frozen target book
```

`venue_funding_observation` is not an execution ledger and should not be queried on the
fill-to-hedge critical path. Execution requires three separate kinds of data:

1. A frozen decision describing exactly what should be traded.
2. Live venue truth describing what can be traded and what has already happened.
3. Durable execution records that allow restart and reconciliation.

The recommended complete flow is:

```text
DynamoDB observations -> PostgreSQL funding data -> frozen signal run
                                                     |
                                                     v
                                              execution worker
                                                /    |    \
                                  public market  private  trade APIs
                                                APIs
                                                     |
                                                     v
                                      PostgreSQL execution ledger

                          continuous risk/reconciliation supervisor
                                      <-> venues and ledger
```

## 2. Frozen execution plan

The execution process should receive a `signalRunId`. It must not rerun ranking after execution has
started, because a restart could otherwise trade a different book.

Minimum persisted signal contract:

```text
SignalRun
  signalRunId
  strategyVersion
  configurationHash
  cutoffUtc
  generatedAt
  source watermark per venue
  status

SignalTarget
  signalRunId and rank
  canonicalAsset
  exact short venue and venue instrument
  exact long venue and venue instrument
  trailing funding for both legs
  annualised spread
  liquidity for both legs
  target USD notional
  next funding timestamp for each leg
  earliest entry and latest acceptable entry
  maximum taker slippage
  selection and rejection reasons
```

The target must preserve both the normalized asset and the exact venue instrument. For example,
`PEPE`, `1000PEPE`, and `kPEPE` can represent the same underlying asset while requiring different
native quantities.

## 3. Live data required before placing an order

### 3.1 Instrument identity and trading rules

For each leg:

- venue instrument symbol and numeric asset/market ID, where applicable;
- product status and perpetual contract type;
- linear or inverse settlement;
- base, quote, margin, and settlement assets;
- underlying units or contract multiplier per native quantity unit;
- price tick;
- quantity step;
- minimum and maximum limit-order quantity;
- minimum and maximum market/IOC quantity;
- minimum notional;
- applicable leverage/risk tier;
- supported time-in-force, post-only, reduce-only, and client-ID semantics.

Rules must be refreshed at process startup. They must not be permanently hard-coded because venues
change filters, limits, and funding schedules.

### 3.2 Funding timing

For each leg:

- current indicated funding rate;
- exact next funding timestamp where the venue exposes it;
- current funding interval;
- funding cap/floor where available;
- venue server or chain time and measured local clock offset.

Binance funding intervals can change dynamically. The live `nextFundingTime` is more authoritative
than a static assumption derived from historical PostgreSQL rows.

For Hyperliquid and dYdX, whose main perpetual markets fund hourly, the scheduler can derive the next
UTC-hour boundary, but it should still validate venue/chain time and the current market state.

### 3.3 Market state

For each leg:

- best bid and ask;
- exchange event timestamp and local receive timestamp;
- sufficient order-book depth to price the target quantity;
- mark and index/oracle price;
- maximum acceptable price for a taker hedge;
- data freshness and sequence status.

The maker venue should be selected from actual depth for the intended quantity, not solely from a
static venue ranking.

### 3.4 Account and risk state

For each dedicated XVF account/subaccount:

- account identity;
- equity and free collateral;
- margin and position mode;
- configured leverage;
- current positions and entry prices;
- liquidation prices or equivalent margin-risk data;
- open orders tagged as belonging to XVF;
- recent order history and fills;
- current maker/taker fee schedule;
- funding payments for post-trade accounting.

The executor should fail closed when it finds an unexplained position or unknown open order.

### 3.5 Order and fill events

Every order and fill needs:

- internal pair and order IDs;
- stable client-supplied venue order ID/idempotency key;
- venue order ID;
- side, price, native quantity, order type, time-in-force, and reduce-only flag;
- accepted, rejected, or unknown submission outcome;
- cumulative filled quantity and remaining quantity;
- unique venue execution/trade ID;
- incremental fill quantity, price, fee asset, fee, and maker/taker classification;
- venue event time and local receive time;
- stream connection, sequence, and recovery state.

A network timeout is an `UNKNOWN` outcome, not a rejection. The adapter must query by the same client
ID before retrying.

## 4. Venue-neutral execution API

The existing placement-only gateway is too narrow for safe execution. A common interface should
cover discovery, reconciliation, placement, cancellation, and streaming:

```java
interface ExecutionVenue {
    VenueCapabilities capabilities();
    Instant serverTime();

    InstrumentSpec instrument(String instrumentId);
    TopOfBook topOfBook(String instrumentId);
    FundingSchedule fundingSchedule(String instrumentId);

    AccountSnapshot accountSnapshot();
    List<PositionSnapshot> positions();
    List<OrderSnapshot> openOrders(String strategyTag);
    Optional<OrderSnapshot> orderByClientId(String clientOrderId);
    List<Fill> fillsSince(FillCursor cursor);

    SubmitResult submit(OrderCommand command);
    CancelResult cancel(OrderRef order);

    UserStream subscribe(StreamCursor cursor, VenueEventListener listener);
}
```

Required common semantics:

- The caller, not the venue adapter, creates and persists the stable client order ID.
- `SubmitResult` distinguishes `ACCEPTED`, `REJECTED`, and `UNKNOWN`.
- Each fill contains a unique venue execution ID for deduplication.
- Order events explicitly identify cumulative versus incremental filled quantity.
- A disconnect or stale stream is an execution event, not merely a log message.
- Unsupported venue behavior is exposed as a capability and is never silently emulated.

The portable order commands should be:

```text
POST_ONLY_LIMIT
CAPPED_IOC_TAKER
REDUCE_ONLY_CAPPED_IOC
```

An explicitly priced IOC is preferable to an unbounded generic `MARKET` order. It still crosses the
book immediately but imposes a worst acceptable price and maps consistently to all four venues.

## 5. Binance USD(S)-M Futures

### Connections

- REST production: `https://fapi.binance.com`
- REST demo: `https://demo-fapi.binance.com`
- Use the current routed Futures WebSocket endpoints documented by Binance. The old
  `wss://fstream.binance.com/ws/<listenKey>` form in `XVF_IMPLEMENTATION.md` is stale under Binance's
  2026 WebSocket routing change.

### Public/preflight APIs

| Purpose | API |
| --- | --- |
| Instrument definitions and filters | `GET /fapi/v1/exchangeInfo` |
| Server clock | `GET /fapi/v1/time` |
| Best bid/ask | `GET /fapi/v1/ticker/bookTicker` |
| Order-book snapshot | `GET /fapi/v1/depth` |
| Mark, index, current funding, next stamp | `GET /fapi/v1/premiumIndex` |
| Adjusted funding interval and cap/floor | `GET /fapi/v1/fundingInfo` |
| Settled funding history, for audit | `GET /fapi/v1/fundingRate` |

Parse `PRICE_FILTER`, `LOT_SIZE`, `MARKET_LOT_SIZE`, and `MIN_NOTIONAL` from `exchangeInfo`.
Binance explicitly warns that `pricePrecision` is not the tick size and `quantityPrecision` is not
the quantity step.

### Account, reconciliation, and trading APIs

| Purpose | API |
| --- | --- |
| Account and balances | `GET /fapi/v3/account`, `GET /fapi/v3/balance` |
| Positions | `GET /fapi/v3/positionRisk` |
| Exact order by venue/client ID | `GET /fapi/v1/order` |
| Open and historical orders | `GET /fapi/v1/openOrders`, `GET /fapi/v1/allOrders` |
| Fills | `GET /fapi/v1/userTrades` |
| Fee rate | `GET /fapi/v1/commissionRate` |
| Leverage/risk data | `GET /fapi/v1/leverageBracket`, `GET /fapi/v1/symbolConfig` |
| Place order | `POST /fapi/v1/order` |
| Cancel one/all for symbol | `DELETE /fapi/v1/order`, `DELETE /fapi/v1/allOpenOrders` |
| Dead-man cancellation | `POST /fapi/v1/countdownCancelAll` |

Order mapping:

- post-only maker: `type=LIMIT`, `timeInForce=GTX`;
- controlled taker: `type=LIMIT`, `timeInForce=IOC`;
- stable idempotency key: `newClientOrderId`;
- closing order in one-way mode: `reduceOnly=true`.

Recommended account configuration is USDT linear perpetuals in one-way mode. Binance does not allow
the same reduce-only semantics in Hedge Mode, which makes a common close path harder to reason about.

Private stream events must include `ORDER_TRADE_UPDATE` and `ACCOUNT_UPDATE`. Binance's order update
field `z` is cumulative filled quantity. Hedging `z` on every partial-fill update would systematically
over-hedge; only the newly observed delta or unique trade should be processed.

An HTTP 503 response can have an unknown execution outcome. Reconcile by `newClientOrderId`; do not
blindly retry.

Official documentation:

- <https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data>
- <https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/trade>
- <https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/account>
- <https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/websocket-market-streams/Important-WebSocket-Change-Notice>

## 6. Bybit V5 linear perpetuals

### Connections

- REST production: `https://api.bybit.com`
- REST testnet: `https://api-testnet.bybit.com`
- Public linear WebSocket: `wss://stream.bybit.com/v5/public/linear`
- Private WebSocket: `wss://stream.bybit.com/v5/private`
- Use `category=linear` consistently.

### Public/preflight APIs

| Purpose | API |
| --- | --- |
| Instrument definitions and filters | `GET /v5/market/instruments-info` |
| Server clock | `GET /v5/market/time` |
| BBO, mark/index, funding and next stamp | `GET /v5/market/tickers` |
| Order-book snapshot | `GET /v5/market/orderbook` |
| Settled funding history, for audit | `GET /v5/market/funding/history` |

The linear instrument endpoint must be paginated. Refresh its limits instead of caching them
permanently because Bybit changes maximum order quantities periodically.

Public WebSocket topics:

- `tickers.{symbol}`;
- `orderbook.{depth}.{symbol}`.

### Account, reconciliation, and trading APIs

| Purpose | API |
| --- | --- |
| Unified account balance | `GET /v5/account/wallet-balance` |
| Account configuration | `GET /v5/account/info` |
| Positions | `GET /v5/position/list` |
| Current/recent orders | `GET /v5/order/realtime` |
| Durable order history | `GET /v5/order/history` |
| Fills | `GET /v5/execution/list` |
| Fee rate | `GET /v5/account/fee-rate` |
| Place order | `POST /v5/order/create` |
| Cancel order/all | `POST /v5/order/cancel`, `POST /v5/order/cancel-all` |
| Configure margin/position/leverage | `/v5/account/set-margin-mode`, `/v5/position/switch-mode`, `/v5/position/set-leverage` |

Order mapping:

- post-only maker: `orderType=Limit`, `timeInForce=PostOnly`;
- controlled taker: `orderType=Limit`, `timeInForce=IOC`;
- stable idempotency key: `orderLinkId`;
- close: `reduceOnly=true`;
- one-way account: `positionIdx=0`.

Subscribe to these private topics:

- `order.linear`;
- `execution.linear`;
- `position.linear`;
- `wallet`.

The create/cancel response is asynchronous acknowledgement, not proof of the final order state.
Use the execution stream as the fill source and deduplicate on `execId`. A single execution message
can contain multiple fills. Bybit can also emit duplicate terminal order updates during a
cancel/fill race.

Bybit's disconnected-cancel-all feature is generally restricted to institutional clients, so a
normal XVF account must use its own watchdog and ordinary cancel-all API.

Official documentation:

- <https://bybit-exchange.github.io/docs/v5/market/instrument>
- <https://bybit-exchange.github.io/docs/v5/market/tickers>
- <https://bybit-exchange.github.io/docs/v5/order/create-order>
- <https://bybit-exchange.github.io/docs/v5/ws/connect>
- <https://bybit-exchange.github.io/docs/v5/websocket/private/execution>
- <https://bybit-exchange.github.io/docs/v5/order/dcp>

## 7. Hyperliquid

### Connections

- REST information: `POST https://api.hyperliquid.xyz/info`
- Signed exchange actions: `POST https://api.hyperliquid.xyz/exchange`
- WebSocket: `wss://api.hyperliquid.xyz/ws`

### Information and account APIs

The `/info` endpoint uses a request `type` to select the operation:

| Purpose | `/info` request type |
| --- | --- |
| Perpetual universe, asset IDs, size precision, max leverage | `meta` |
| Mark/oracle/mid, current funding, open interest | `metaAndAssetCtxs` |
| Depth | `l2Book` |
| Account and positions | `clearinghouseState` |
| Current orders | `openOrders` or `frontendOpenOrders` |
| Exact order | `orderStatus` |
| Fills | `userFills` or `userFillsByTime` |
| Historical orders | `historicalOrders` |

Query account state with the actual master/subaccount address, not the API/agent-wallet address.

Public WebSocket subscriptions should include `bbo` or `l2Book`. Private subscriptions should
include `orderUpdates`, `userFills`, and the relevant account-state/open-order streams.

### Exchange actions

Order mapping in the signed `order` action:

- post-only maker: limit TIF `Alo`;
- controlled taker: limit TIF `Ioc`;
- normal resting limit: TIF `Gtc`;
- stable idempotency key: `cloid`, a 128-bit hexadecimal client order ID;
- closing order: `reduceOnly`;
- cancel: order ID or `cancelByCloid`;
- dead-man cancellation: `scheduleCancel`;
- leverage/margin configuration: `updateLeverage`.

Hyperliquid enforces venue-specific price and quantity precision. Size uses the market's
`szDecimals`; price is additionally constrained by significant figures and allowed decimal places.
The minimum perpetual order notional is currently USD 10.

Use a dedicated approved API/agent wallet and unique atomic nonces. Signing is easy to get subtly
wrong; the official SDK should be treated as the signing reference, with golden signature fixtures
for any Java implementation.

Official documentation:

- <https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint>
- <https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals>
- <https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint>
- <https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions>
- <https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/nonces-and-api-wallets>

## 8. dYdX v4

dYdX separates reads from writes:

- market/account reads and WebSockets use the Indexer;
- signed order and cancellation transactions go to a validator/OEGS node;
- orders must not be submitted to the Indexer.

### Connections

- Indexer REST: `https://indexer.dydx.trade/v4`
- Indexer WebSocket: `wss://indexer.dydx.trade/v4/ws`
- OEGS/validator access: use the official current node endpoints, for example the documented OEGS
  gRPC/RPC service.

### Read and stream APIs

Required Indexer resources:

- perpetual-market definitions and market IDs;
- current oracle/market state and funding information;
- order books and market streams;
- subaccount balances and positions;
- open and historical orders;
- exact order lookup;
- fills;
- authenticated subaccount stream containing order, fill, and position updates.

The principal market-discovery endpoint is `GET /v4/perpetualMarkets`. Use the official Indexer REST
and WebSocket clients/endpoints for orderbook and subaccount state.

### Trading through the node

Place and cancel orders as signed Cosmos transactions, normally through the official `NodeClient`
semantics (`MsgPlaceOrder` and the corresponding cancellation message).

Order mapping:

- maker: limit order with `POST_ONLY`;
- taker/hedge: explicitly priced `IOC`;
- close: `reduceOnly`;
- stable idempotency component: persisted 32-bit `clientId`;
- persist the complete derived dYdX order identity, which also includes address, subaccount,
  order flags, and market ID.

A transaction hash or broadcast response is not fill confirmation. Confirm the order and fills
through the Indexer/subaccount stream and reconciliation APIs.

dYdX short-term orders expire after a small number of blocks, roughly 20 blocks under the current
protocol constraint. That does not naturally fit XVF's approximately one-minute maker attempt.
The design must choose one of:

1. A stateful/long-term post-only maker order with a tight Good-Til-Block-Time.
2. A maker timeout short enough to fit a short-term order's Good-Til-Block limit.

The taker hedge should be a short-term IOC with a tight block expiry and explicit worst price.
Multiple stateful orders also require careful Cosmos account-sequence handling.

The official SDKs do not currently provide a first-party Java client. A Java implementation needs
protobuf/signing fixtures verified against an official SDK, or a separately reviewed signer
boundary.

Official documentation:

- <https://docs.dydx.xyz/interaction/endpoints>
- <https://docs.dydx.xyz/interaction/trading>
- <https://docs.dydx.xyz/concepts/trading/orders>
- <https://docs.dydx.xyz/types/time_in_force>
- <https://docs.dydx.xyz/indexer-client/http>
- <https://docs.dydx.xyz/indexer-client/websockets>

## 9. Sizing and residual exposure

The target is equal USD notional/equivalent underlying exposure, not equal native order quantity.

Conceptually:

```text
targetUnderlying = targetUsdNotional / referencePrice

nativeQuantityOnVenueA =
    roundToVenueStep(targetUnderlying / underlyingUnitsPerNativeUnitA)

nativeQuantityOnVenueB =
    roundToVenueStep(targetUnderlying / underlyingUnitsPerNativeUnitB)
```

After rounding, calculate the USD or underlying residual and reject the pair if it exceeds a defined
tolerance. Validate tick size, minimum notional, maximum order size, price bands, and risk tier on
both legs independently.

The current strategy sizing formula remains:

```text
legNotional = capital * LEG_LEVERAGE / (POSITIONS * 2)
```

All calculations should use `BigDecimal`; all venue/exchange timestamps should use UTC `Instant`.

## 10. Execution and recovery sequence

Each start, including restart after failure, should perform the same sequence:

1. Acquire a lease and fencing token for the rebalance slot.
2. Load one frozen `signalRunId` and its target book.
3. Query every venue for balances, account modes, positions, XVF-tagged orders, and recent fills.
4. Rebuild local truth from venue truth; do not trust only the previous in-memory state.
5. Resolve/cancel stale maker orders and confirm their terminal state.
6. Process any cancel/fill race and hedge residual exposure immediately.
7. Refuse new entries while any unknown order, position, or unexplained exposure remains.
8. Open private streams and wait for authenticated subscription readiness.
9. Take another REST snapshot after subscription to close the snapshot/stream race.
10. Validate instrument rules, funding times, book freshness/depth, collateral, and account modes.
11. Persist the maker intent and stable client order ID before submission.
12. Place a post-only maker order on the thinner leg.
13. On each unique maker fill, calculate only the new underlying-exposure delta and send its hedge.
14. Confirm taker fills; submission acknowledgement alone does not mean the pair is hedged.
15. At the maker deadline, cancel and confirm terminal state before handling any unfilled remainder.
16. Continue until every pair is balanced, skipped, or explicitly placed in manual intervention.
17. Perform final positions/orders/fills reconciliation before the entry worker exits.

The hedge requirement is based on confirmed exposure, not repeated cumulative events:

```text
hedgeRequiredUnderlying =
    makerConfirmedUnderlyingFilled
  - takerConfirmedUnderlyingFilled
```

On private-stream failure:

- stop creating new maker exposure;
- reconcile orders and fills through REST immediately;
- cancel still-working makers where safe;
- continue hedging already-confirmed exposure;
- do not treat a successful reconnect as proof that no events were missed.

## 11. Durable PostgreSQL execution records

Minimum proposed tables:

```text
xvf_execution_run
  run ID, signal run ID, rebalance slot, mode, lifecycle state,
  lease owner, fencing token, timestamps

xvf_pair_intent
  run ID, action, canonical asset, both venue instruments,
  target notional/underlying exposure, maker/taker choice,
  funding timestamps, execution window, residual tolerance, state

xvf_order_intent
  pair, role, venue/account/instrument, stable client order ID,
  side/type/TIF/post-only/reduce-only, quantity, price,
  submission outcome, venue order ID

xvf_order_event
  unique venue event identity, order IDs, state,
  cumulative fill, venue/receive timestamps, raw payload

xvf_fill
  unique venue trade ID, order, quantity, price,
  fee/currency, maker/taker, venue event time

xvf_reconciliation_snapshot
  venue/account, observation time, balances, positions, open orders

xvf_execution_alert
  severity, run/pair, condition, resolution/acknowledgement timestamps
```

Orders and fills should be append-only. Derived pair state can be rebuilt from these records plus a
fresh venue reconciliation.

## 12. Security and operational requirements

- Use a dedicated XVF subaccount on every venue.
- Restrict CEX keys to read and trading only.
- Disable withdrawals and IP-allowlist keys where supported.
- Store secrets in an approved secret manager, not command-line system properties.
- Use a dedicated Hyperliquid agent/API wallet.
- Use a permissioned/restricted dYdX trader key or authenticator where supported.
- Never log signatures, secrets, private keys, full authenticated request headers, or raw key
  material.
- Prefer direct official API adapters to a generic exchange abstraction such as CCXT. Post-only,
  reduce-only, position mode, client IDs, fill semantics, and dead-man behavior are safety controls,
  not incidental differences to flatten away.

## 13. Decisions still requiring explicit agreement

### 13.1 Unfilled maker after approximately one minute

`XVF_STRATEGY.md` assumes that the laggard is crossed after about one minute. The current code
abandons an unfilled maker instead. These produce different position books and economics.

Recommended behavior for strategy parity:

1. Cancel the maker remainder.
2. Confirm terminal status and process the cancel/fill race.
3. If the funding window remains valid, cross the residual on both venues with capped IOC orders and
   an imbalance handler.
4. If the stamp window is no longer valid, skip/wait for the next valid stamp rather than chase.

### 13.2 Short-lived execution versus three-day position supervision

The entry worker can exit after every pair is terminal and reconciled. Positions held for three days
still require either:

- a continuously running risk/reconciliation supervisor; or
- reliable venue-native reduce-only protective orders plus an independent health monitor.

Cross-venue imbalance and venue outages cannot be managed safely by a process that wakes only every
three days. The recommended architecture is a short-lived rebalance worker plus a small continuous
risk supervisor.

### 13.3 Actual maker venue

The current static ranking `dYdX < Hyperliquid < Bybit < Binance` should be replaced by actual depth
and expected slippage for the proposed leg quantity. Static ranking can be retained only as a
fallback or tie-breaker.

### 13.4 Account modes

Recommended initial CEX scope is USDT linear perps, one-way position mode, and a dedicated strategy
subaccount. Hedge mode and portfolio margin introduce materially different close and risk semantics
and should be a separate reviewed capability.

### 13.5 SDK/signing boundary

Binance and Bybit can be implemented directly with Java `HttpClient`, Jackson, HMAC signing, and
fixture-based tests. Hyperliquid and dYdX require more complex cryptographic/protocol signing and
need an explicit decision between:

- native Java implementations verified against official SDK golden fixtures; or
- a separately deployed and reviewed signer/SDK boundary.

## 14. Recommended implementation order

1. Persist frozen signal runs and target books.
2. Add the execution ledger schema.
3. Implement read-only instrument/account/order/position/fill reconciliation for all four venues.
4. Produce a reconciliation report and verify restart behavior with no trading permissions.
5. Implement the venue-neutral pair state machine and simulated/paper fills.
6. Enable Binance and Bybit order placement in test environments, then tiny live canaries.
7. Add Hyperliquid signing/order placement after golden-signature verification.
8. Add dYdX last because it has the most distinct write path, expiry, and transaction semantics.
9. Enable only one small cross-venue pair at first; reconcile it through entry, funding receipts,
   exit, and restart before increasing the book.

## 15. Checklist for independent AI review

An independent reviewer should validate at least these questions against current official APIs:

1. Are every REST and WebSocket base URL and endpoint path still current?
2. Are post-only, IOC, reduce-only, client-ID, and position-mode mappings correct per venue?
3. Can every ambiguous submission be queried safely using the client-supplied ID?
4. Are fill IDs unique and are reported quantities cumulative or incremental?
5. What initial snapshot and sequence rules are required for every WebSocket channel?
6. What are the precise private-stream expiration, reconnect, and gap-recovery requirements?
7. Is each dead-man/cancel-on-disconnect feature available to an ordinary account?
8. Is the next funding timestamp directly exposed, or must it be derived, for every venue?
9. Are contract multiplier and quantity semantics sufficient to match underlying exposure across
   normalized instruments?
10. Are market-order protections better expressed as explicitly priced IOC orders on every venue?
11. What dYdX order class and expiry correctly support the intended maker duration?
12. Are Hyperliquid and dYdX signing/nonce/sequence rules completely represented?
13. Can the executor reconstruct all positions, orders, and fills after a total local-state loss?
14. Which venue-native protective orders can remain active while the rebalance worker is stopped?
15. Are rate limits sufficient for twenty pairs during a simultaneous rebalance and emergency
    cancellation?

