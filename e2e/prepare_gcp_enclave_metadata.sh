#!/usr/bin/env bash
set -ex

ROOT="."
METADATA_ROOT="$ROOT/docker/localstack/s3/core"
OPERATOR_FILE="$METADATA_ROOT/operators/operators.json"
ENCLAVE_FILE="$METADATA_ROOT/enclaves/enclaves.json"

if [ -z "$IMAGE_HASH" ]; then
  echo "IMAGE_HASH can not be empty"
  exit 1
fi

# generate enclave id
enclave_str="V1,true,$IMAGE_HASH"
echo "enclave_str=$enclave_str"
enclave_id=$(echo -n $enclave_str | openssl dgst -sha256 -binary | openssl base64)


# fetch operator key
OPERATOR_KEY=$(jq -r '.[] | select(.protocol=="gcp-oidc") | .key' $OPERATOR_FILE)

# update gcp-oidc enclave id
cat <<< $(jq '(.[] | select(.protocol=="gcp-oidc") | .identifier) |='\"$enclave_id\"'' $ENCLAVE_FILE) > $ENCLAVE_FILE

# export to Github output
echo "OPERATOR_KEY=$OPERATOR_KEY"

if [ -z "$GITHUB_OUTPUT" ]; then
  echo "not in github action"
else
  echo "OPERATOR_KEY=$OPERATOR_KEY" >> $GITHUB_OUTPUT
fi
