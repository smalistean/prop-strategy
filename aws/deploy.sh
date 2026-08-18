#!/bin/bash
# Builds the recorder jar and deploys the stack.
#
# Idempotent: re-run it after any code or template change and it updates in place. The DynamoDB table
# carries DeletionPolicy: Retain, so it survives a stack delete - these quotes cannot be re-fetched
# at any price, and an accidental teardown must not be able to destroy hours that were never
# exported to Postgres.
set -euo pipefail

# Two independent stacks sharing one jar. They record different data on different schedules and have
# no resources in common, so a problem with one cannot take the other down.
#   deribit-chain  option-chain snapshots  -> deribit-chain-chain
#   xvf-funding    venue funding rates     -> xvf-funding-observation
#   xvf-signal     frozen target books     -> xvf-signal-book
# Pass STACKS=xvf-funding to deploy just one.
#
# Provisioned capacity is split across them on purpose. The DynamoDB free allowance is 18,600
# WCU-hours a month PER REGION, i.e. 25 WCU shared by every table in the account - not 25 each.
# Budget: W 14+10+1 = 25, R 10+9+2 = 21.
STACK="${STACK:-deribit-chain}"
FUNDING_STACK="${FUNDING_STACK:-xvf-funding}"
SIGNAL_STACK="${SIGNAL_STACK:-xvf-signal}"
STACKS="${STACKS:-$STACK $FUNDING_STACK $SIGNAL_STACK}"
REGION="${REGION:-$(aws configure get region || echo eu-central-1)}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Pinned, NOT ${JAVA_HOME:-...} - an interactive shell here has sdkman's current JDK (11) in
# JAVA_HOME, which cannot build this. Same reasoning as scripts/deribit-snapshot.sh.
JAVA_HOME_BUILD="${PROP_JAVA_HOME:-/opt/homebrew/opt/openjdk@25}"

if [ -z "${ALERT_EMAIL:-}" ]; then
    echo "ALERT_EMAIL is required, e.g.  ALERT_EMAIL=you@example.com $0" >&2
    exit 1
fi

echo "==> building recorder (targets Java 21: Lambda has no 25 runtime)"
JAVA_HOME="$JAVA_HOME_BUILD" mvn -q -B -f "$HERE/recorder/pom.xml" clean package

for stack in $STACKS; do
    case "$stack" in
        "$FUNDING_STACK")
            template="$HERE/funding-template.yaml"
            params=("AlertEmail=$ALERT_EMAIL" "RetentionDays=${RETENTION_DAYS:-30}")
            ;;
        "$SIGNAL_STACK")
            template="$HERE/signal-template.yaml"
            params=("RetentionDays=${SIGNAL_RETENTION_DAYS:-365}" "LookbackDays=${LOOKBACK_DAYS:-7}")
            ;;
        *)
            template="$HERE/template.yaml"
            params=("AlertEmail=$ALERT_EMAIL" "RetentionDays=${RETENTION_DAYS:-30}"
                    "DeribitCurrencies=${DERIBIT_CURRENCIES:-BTC,ETH,USDC}")
            ;;
    esac

    echo "==> deploying stack '$stack' to $REGION"
    sam deploy \
        --template-file "$template" \
        --stack-name "$stack" \
        --region "$REGION" \
        --capabilities CAPABILITY_IAM \
        --resolve-s3 \
        --no-confirm-changeset \
        --no-fail-on-empty-changeset \
        --parameter-overrides "${params[@]}"
done

echo
echo "==> outputs"
for stack in $STACKS; do
    echo "--- $stack"
    aws cloudformation describe-stacks --stack-name "$stack" --region "$REGION" \
        --query 'Stacks[0].Outputs[].[OutputKey,OutputValue]' --output table
done

cat <<EOF

Next:
  1. Confirm the SNS subscription email that just arrived, or failures will alert nobody.
  2. Smoke-test one hour without waiting for the schedule:
       aws lambda invoke --function-name ${STACK}-recorder --region $REGION /dev/stdout
  3. Pull it into Postgres:
       mvn -q compile exec:java \\
         -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.DeribitDynamoExportApplication \\
         -DdynamoTable=${STACK}-chain -DawsRegion=$REGION
EOF
