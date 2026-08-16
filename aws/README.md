# AWS deployment — Deribit option-chain recorder

Moves the hourly snapshot off the laptop and into Lambda, keeping analysis in local Postgres.

**Why:** a missed hour is permanent. Deribit serves trade history for free but not quote history, so
the data exists only if it was captured at the time. Two home-network DNS outages inside 24 hours
cost 9 hours and nearly a tenth. Nothing about the compute needs a cloud — the availability does.

---

## Shape

```
EventBridge rule  cron(5 * * * ? *)     free, and invokes Lambda ASYNCHRONOUSLY
        │
        ▼
Lambda  DeribitRecorderHandler          java21 / arm64 / 512MB
        │  6 Deribit calls, ~4,046 items
        ▼
DynamoDB  <stack>-chain                 30-day TTL — a buffer, not the archive
        │
        ▼  scripts/deribit-export.sh — MANUAL, nothing schedules it
Postgres  deribit_option_quote          the archive
```

DynamoDB holds the hours because it is up when your connection is not. Postgres holds the archive
because every question this dataset exists for is an aggregate or a join, and DynamoDB does neither.

## Cost

Everything sits inside the always-free tier, so **$0/month**. Utilisation:

| Service | Free allowance | Used | |
| --- | --- | ---: | ---: |
| Lambda compute | 400,000 GB-s/mo | 1,825 GB-s | 0.5% |
| Lambda requests | 1M/mo | 730 | 0.07% |
| DynamoDB writes | 25 WCU = 65.7M writes/mo | 2.95M | 4.5% |
| DynamoDB storage | 25 GB | 1.09 GB at 30-day TTL | 4.3% |
| Data transfer out | 100 GB/mo | ~1.6 GB | 1.6% |
| EventBridge rules | unmetered | 730 | — |

Two things would end that. **Dropping the TTL**: storage then grows ~13.2 GB/year and crosses 25 GB
around month 23, reaching about $3.65/month by year three. **Switching the table to
`PAY_PER_REQUEST`**: on-demand writes cost ~$1.85/month where 25 provisioned WCU cost nothing.

Note what retention does *not* cost. At 30 days the table holds 1.09 GB of a 25 GB allowance, so
retention could be raised past **600 days** before a single cent is billed. The 30-day setting buys
nothing financially — it is purely a choice about how much margin to leave for a missed export.

Point-in-time recovery is off on purpose. It is the prerequisite for DynamoDB's native "Export to
S3" and costs $0.20/GB-month — more than the rest of this stack combined. The exporter uses `Query`,
which needs no PITR.

## Deploy

```bash
ALERT_EMAIL=you@example.com bash aws/deploy.sh
```

Optional environment: `STACK` (default `deribit-chain`), `REGION` (default from your AWS config),
`RETENTION_DAYS` (30), `DERIBIT_CURRENCIES` (`BTC,ETH,USDC`).

Changing `RETENTION_DAYS` affects only items written *after* the redeploy — `expires_at` is stamped
at write time, so hours already stored keep whatever TTL they were given.

**Confirm the SNS subscription email**, or failures alert nobody.

Smoke-test one hour rather than waiting for the schedule:

```bash
aws lambda invoke --function-name deribit-chain-recorder /dev/stdout
```

## Export to Postgres

```bash
bash scripts/deribit-export.sh
```

Incremental by default: resumes from the newest hour already in Postgres. Re-running is harmless —
the insert is `ON CONFLICT DO NOTHING` against the same primary key. Override with the environment
variables `DYNAMO_TABLE`, `AWS_REGION`, or `EXPORT_FROM=2026-08-16T00:00:00Z` to force a range.

**Run it at least every 30 days.** Nothing schedules this — not locally, not in AWS — so it is the
one manual step keeping the data. An hour not exported inside the TTL window is deleted with no
error, and Deribit does not sell quote history back. 30 days is the entire margin.

## Design decisions worth knowing

**Partition key is `snapshot_underlying`, not `snapshot_time`.** All 4,046 items of one snapshot
under a single partition key would land in one partition, and a DynamoDB partition is capped at
1,000 WCU/sec however much table capacity is provisioned — so the write would throttle on the
partition limit alone. Splitting by underlying gives nine partitions whose largest, BTC at 818
items, sits under the ceiling.

**EventBridge rule, not EventBridge Scheduler.** Rules are unmetered where Scheduler is $1/million,
but the deciding reason is that rules invoke Lambda *asynchronously*. Async invocation is what
enables `MaximumRetryAttempts` and the on-failure destination; a synchronous invoke gets neither.
Scheduler's own retry only covers delivery failures — a Deribit DNS error inside the function is a
*successful* invocation returning an error, which Scheduler would never retry.

**Retry is layered, and the layer that matters is Lambda's.**

| Layer | Horizon | Would it have caught 2026-08-16? |
| --- | --- | --- |
| In-function HTTP retry | 120s per call, 600s per invocation | yes |
| Lambda async retry (2 attempts) | up to 2,400s | yes |
| On-failure → SNS → email | — | tells you |
| Schedule slack (`:05`, hour-truncated) | 55 min | yes |

The laptop version retried 4 times over **14 seconds**. That morning's outage lasted at least three
minutes, so it never had a chance — the horizon, not the attempt count, was the bug.

`MaximumEventAgeInSeconds` is 2,400 rather than the 6-hour maximum on purpose: past the hour
boundary a retry writes a *different* hour's timestamp, so the slot it was meant to fill is lost
anyway and the late attempt is wasted work that reports success.

**Idempotency.** The snapshot instant is truncated to the hour, so any retry inside the same hour
rewrites the same items instead of creating a partial second copy. This is the property that made
manual recovery of the 05:00 hour work on 2026-08-16, three minutes after the scheduled attempt
failed.

**A MANIFEST item is written last.** It names the underlyings and item count for that hour, and its
presence is the marker that the hour is complete. The exporter reads it to learn which partitions to
query and checks what it actually pulled against it. Both failure modes here are silent by nature —
a half-written hour looks like a thin market, an expired hour looks like one that never existed — so
they are reported at export time rather than discovered months later. It also means a chain Deribit
adds later is picked up automatically instead of being skipped by a hardcoded list.

**The recorder refuses to record a partial hour as complete.** Under 1,000 instruments throws rather
than returning, because succeeding on a truncated book writes a hole that looks like a recorded
hour: the retry never fires and nothing ever reports it.

## Known gaps

1. **Nothing detects the schedule never firing.** The on-failure destination reports invocations that
   *ran and failed*. A disabled rule, a deleted function or a suspended account produces silence,
   which looks identical to everything working. The exporter's missing-hour report is what catches
   this — which is a reason to run it on a schedule rather than when you happen to think of it.
2. **The export is manual, and the TTL is 30 days.** By choice, 2026-08-16: nothing schedules
   `scripts/deribit-export.sh`, locally or in AWS. The archive therefore depends on running it inside
   a 30-day window, every time, indefinitely. Missing that window loses hours permanently and
   silently — no alarm covers it, because AWS cannot know whether an item was exported before it
   expired.

   This is the project's recurring failure shape: the tardis agent died unnoticed, the deribit job
   failed for nine hours unnoticed, and both were *scheduled*. A step that depends on memory has
   less protection than either. Raising `RetentionDays` costs nothing until ~600 days, so the margin
   is free whenever it is wanted.

3. **The laptop is no longer a fallback.** The launchd agent
   `com.smalistean.propstrategy.deribit-snapshot` was removed on 2026-08-16, so the Lambda is the
   only thing recording. `scripts/deribit-snapshot.sh` still works for manual backfill straight into
   Postgres, bypassing DynamoDB entirely — useful if an export window is ever missed while the hour
   is still current.
4. **`root` credentials.** `aws sts get-caller-identity` reports the account root. Deploying from an
   IAM user or role with scoped permissions would be better practice; root access keys cannot be
   scoped and cannot be revoked without rotating everything.
5. **Everything else is verified.** The exporter's INSERT path was the last untested link and was
   proven at hour 07:00Z on 2026-08-16: Postgres went 322,324 → 326,370 rows on an hour no local
   process had written. The whole chain — rule fires unattended, Lambda writes, manifest matches,
   exporter inserts — has now run end to end.

## Verified live, 2026-08-16

| Step | Result |
| --- | --- |
| Lambda invoke | `DERIBIT SNAPSHOT 2026-08-16T06:00:00Z: 4,046 items across 9 underlyings`, 40s cold start |
| DynamoDB write | 4,046 items, no throttling |
| Manifest | `BTC=818,ETH=690,BTC_USDC=538,ETH_USDC=476,SOL_USDC=460,TRX_USDC=292,XRP_USDC=290,HYPE_USDC=278,AVAX_USDC=204` |
| TTL | at deploy time 90 days; changed to **30** the same day, verified on hour 07:00Z → expires 2026-09-15T07:00Z |
| Export read | 4,046 pulled, matched the manifest, 0 missing, 0 incomplete |
| Export insert | **not exercised** — see gap 4 |

Comparing the same instrument in both stores shows why running two recorders is untidy rather than
wrong. `BTC-25SEP26-70000-C` at hour 06:00:

| | Postgres (laptop, 06:05) | DynamoDB (Lambda, ~06:40) |
| --- | --- | --- |
| strike, type, bid, ask, open_interest | identical | identical |
| mark_price | 0.01089711 | 0.01086311 |
| mark_iv | 32.72 | 32.74 |
| underlying_price | 63,311.89 | 63,293.32 |

The keys and the quotes agree; the mark, IV and index moved 0.03% over the 35 minutes between
captures. That is real market drift, not a mapping error — but it means that while both recorders
run, an hour's row is "captured somewhere inside that hour" rather than at a consistent offset,
because hour-truncation makes the first writer win. Retire one before the timing matters.
