#!/bin/bash
# Builds the Nuxt signal page and publishes it to the S3 website bucket.
#
# The API URL is read from the CloudFormation stack rather than hardcoded, so the page cannot end up
# pointing at a Lambda URL that changed underneath it. Both the bucket name and the endpoint come
# from the same stack that created them.
set -euo pipefail

STACK="${SIGNAL_STACK:-xvf-signal}"
REGION="${REGION:-$(aws configure get region || echo eu-central-1)}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

out() {
    aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
        --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

API_URL="$(out SignalApiUrl)"
BUCKET="$(out SiteBucketName)"
SITE_URL="$(out SiteUrl)"

if [ -z "$API_URL" ] || [ "$API_URL" = "None" ]; then
    echo "no SignalApiUrl on stack '$STACK' - deploy aws/signal-template.yaml first" >&2
    exit 1
fi

echo "==> building against $API_URL"
cd "$HERE/web"
npm install --silent
NUXT_PUBLIC_SIGNAL_API_URL="$API_URL" npx nuxt generate

echo "==> publishing to s3://$BUCKET"
# Hashed assets are immutable and safe to cache hard; the HTML entry point must not be, or a deploy
# is invisible until a browser cache expires.
aws s3 sync .output/public "s3://$BUCKET" --region "$REGION" --delete \
    --exclude "*.html" --cache-control "public,max-age=31536000,immutable"
aws s3 sync .output/public "s3://$BUCKET" --region "$REGION" \
    --exclude "*" --include "*.html" --cache-control "no-cache"

echo
echo "==> live at $SITE_URL"
