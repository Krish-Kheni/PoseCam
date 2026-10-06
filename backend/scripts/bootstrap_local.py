"""Create the S3 bucket + DynamoDB table on a LOCAL emulator (moto server, DynamoDB Local, MinIO...).

Usage (after exporting the same env vars you use for uvicorn):

    python scripts/bootstrap_local.py

It mirrors template.yaml (key schema, versioning). Never run it against real AWS: use `sam deploy`.
"""

from __future__ import annotations

import os
import sys

import boto3
from botocore.exceptions import ClientError


def main() -> int:
    s3_endpoint = os.environ.get("S3_ENDPOINT_URL")
    ddb_endpoint = os.environ.get("DYNAMODB_ENDPOINT_URL")
    if not (s3_endpoint or ddb_endpoint):
        print("Refusing to run without S3_ENDPOINT_URL / DYNAMODB_ENDPOINT_URL (would hit real AWS).")
        return 2
    region = os.environ.get("AWS_REGION") or os.environ.get("AWS_DEFAULT_REGION") or "us-east-1"
    bucket = os.environ["S3_BUCKET"]
    table = os.environ["DYNAMODB_TABLE"]

    if s3_endpoint:
        s3 = boto3.client("s3", region_name=region, endpoint_url=s3_endpoint)
        try:
            kwargs = {} if region == "us-east-1" else {"CreateBucketConfiguration": {"LocationConstraint": region}}
            s3.create_bucket(Bucket=bucket, **kwargs)
            s3.put_bucket_versioning(Bucket=bucket, VersioningConfiguration={"Status": "Enabled"})
            print(f"created bucket {bucket}")
        except ClientError as exc:
            print(f"bucket: {exc.response['Error']['Code']}")

    if ddb_endpoint:
        ddb = boto3.client("dynamodb", region_name=region, endpoint_url=ddb_endpoint)
        try:
            ddb.create_table(
                TableName=table,
                AttributeDefinitions=[
                    {"AttributeName": "PK", "AttributeType": "S"},
                    {"AttributeName": "SK", "AttributeType": "S"},
                ],
                KeySchema=[
                    {"AttributeName": "PK", "KeyType": "HASH"},
                    {"AttributeName": "SK", "KeyType": "RANGE"},
                ],
                BillingMode="PAY_PER_REQUEST",
            )
            print(f"created table {table}")
        except ClientError as exc:
            print(f"table: {exc.response['Error']['Code']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
