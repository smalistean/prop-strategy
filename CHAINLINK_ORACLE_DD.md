# Chainlink ETH/USD feed — the dependency under every liquidation

**Date:** 2026-09-02 03:55 UTC · **Author:** Claude
**Why:** `AAVE_LIQUIDATION_DD.md` found that Aave's entire liquidation engine reduces to one
`IPriceOracleGetter.getAssetPrice()` call — the health factor, the liquidator's profit and the
cascade's timing are all downstream of a feed we had not opened. This opens it. Tenth and final
in the contract-reading series.
**Contracts:** proxy `EACAggregatorProxy` `0x5f4eC3Df9cbd43714FE2740f5E3616155c5b8419` (Solidity
0.6.6) → aggregator `AccessControlledOCR2Aggregator`
`0x7d4e742018fb52e48b08be73d041c18b21de6fb5` (0.8.19, `typeAndVersion` = "AccessControlledOCR2Aggregator 1.0.0").

## Live state (2026-09-02)

| Field | Value |
|---|---|
| ETH/USD | **$2,421.52**, updated 35 min before reading |
| decimals | 8 |
| transmitters (nodes able to post on-chain) | **31** |
| config revision | 8 (last set at block 25,557,130) |
| **minAnswer** | **1** (i.e. $0.00000001) |
| **maxAnswer** | **2^176 − 1** (~$9.6 × 10^44) |

## How a price actually lands on-chain

Consumers read the **proxy**, never the aggregator directly — the proxy holds a swappable pointer
(`aggregator()`) so Chainlink can migrate implementations without every integrator changing an
address. The aggregator is **OCR2**: 31 whitelisted transmitters observe off-chain, agree a report
off-chain, and one of them posts the aggregated result in a single transaction. The on-chain
contract does not gather prices — it **verifies a signed report and stores the median**:

```solidity
int192 median = report.observations[report.observations.length/2];
require(minAnswer <= median && median <= maxAnswer, "median is out of min-max range");
```

Two consequences worth naming. The price is a **median of off-chain observations**, so
manipulating it requires corrupting a majority of a 31-node set, not winning one trade — which is
why lending protocols prefer this to a spot DEX read. And updates are **discrete**: the feed only
writes when the off-chain nodes decide to (deviation threshold or heartbeat, both configured
off-chain and not readable here). That is the mechanical reason Aave liquidations arrive in
clusters — many positions cross `HF < 1` on the *same* oracle write, not continuously as the
market moves.

## The finding: the LUNA failure mode has been disarmed on this feed

Line 778 is a `require`, not a clamp. If the true median falls outside `[minAnswer, maxAnswer]`
the transmission **reverts**, so the feed does not report a wrong price — it **stops updating and
freezes at its last in-range value**. That is precisely the 2022 Venus/LUNA disaster: LUNA's feed
had a real floor, LUNA crashed through it, the feed froze at the floor, and a lending protocol
kept valuing collateral at a price that no longer existed.

On this feed that cannot happen: **minAnswer is 1 and maxAnswer is 2^176 − 1** — non-binding by
many orders of magnitude. ETH would have to fall 100% or rise by a factor of 10^42 to reach a
bound. The circuit breakers still exist in the code but have been widened until they cannot fire.
This is a real, verifiable improvement over the OCR1-era feeds, and it is the kind of thing only a
contract read shows: the mechanism is unchanged, the *parameters* are what made it safe.

## What remains a genuine dependency

- **Off-chain by design.** Deviation threshold and heartbeat — the parameters deciding *when* a
  price exists on-chain at all — are node configuration, not contract state. They are not
  auditable from here. The staleness a consumer sees (`updatedAt`) is the only on-chain evidence.
- **The proxy pointer is swappable.** Whoever owns the proxy can repoint `aggregator()` at a
  different contract; every consumer follows silently. That is the single highest-leverage key in
  the entire dependency chain — Aave's liquidations included.
- **31 transmitters is a trust set, not a trustless mechanism.** Better than one reporter by a
  wide margin, and still a defined group whose collusion or compromise is the failure mode.
- **Consumers must check staleness themselves.** The contract will happily serve an old
  `latestRoundData`; nothing forces a consumer to reject it. Aave's wrapper, not this contract,
  decides what "too old" means.

## Closing the series' loop

`AAVE_LIQUIDATION_DD.md` said the liquidation cascade is "a function of an oracle read."
Concretely, that read is: a 31-node off-chain median, written discretely, through a swappable
proxy, with circuit breakers deliberately set wide enough never to trigger. The cascade dynamics
we measured — many positions crossing at once, forced selling arriving in bursts — are a direct
consequence of the *discreteness* of that write, not of anything in Aave's own code.

## Where Chainlink sits in the series

| System | Who can change what you see | Constraint |
|---|---|---|
| Uniswap V3 | nobody (price is pool state) | immutable |
| Curve 3pool | owner tunes A/fee | rate-limited + 3-day timelock |
| **Chainlink feed** | **proxy owner can swap the aggregator** | **none on-chain; 31-node median below it** |
| Aave | governance sets risk params | upgradeable |
| USDC / USDT | one key freezes / destroys | none |

## Sources

- Verified source via Blockscout Ethereum for both proxy and aggregator, 2026-09-02
- Live state via `eth_call` (`latestRoundData`, `minAnswer`, `maxAnswer`, `getTransmitters`,
  `latestConfigDetails`, `typeAndVersion`) on `ethereum-rpc.publicnode.com`; function selectors
  computed with a locally implemented Keccak-256 (self-tested against the empty-string digest)
- Series companions: `AAVE_LIQUIDATION_DD.md`, `CURVE_STABLESWAP_DD.md`, `UNISWAP_V3_DD.md`,
  `USDT_ISSUER_POWERS_DD.md`, `USDC_ISSUER_POWERS_DD.md`, `ETHENA_USDE_DD.md`,
  `HYPERLIQUID_BRIDGE_DD.md`, `HYPERLIQUID_HLP_DD.md`, `VARIATIONAL_CONTRACT_DD.md`
