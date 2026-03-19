#!/usr/bin/env bash
set -euo pipefail

# Shared by ECS rollout jobs to resolve the latest image tags from ECR.

format="shell"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --format)
      format="$2"
      shift 2
      ;;
    *)
      echo "Unsupported argument: $1" >&2
      exit 1
      ;;
  esac
done

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "::error::${name} is required." >&2
    exit 1
  fi
}

emit_var() {
  local key="$1"
  local value="$2"

  case "$format" in
    shell)
      printf 'export %s=%q\n' "$key" "$value"
      ;;
    env)
      printf '%s=%s\n' "$key" "$value"
      ;;
    *)
      echo "Unsupported format: $format" >&2
      exit 1
      ;;
  esac
}

latest_ecr_tag_for_prefix() {
  local repository="$1"
  local prefix="$2"
  local fallback="$3"
  local tags=""
  local latest_tag=""

  if [[ "$prefix" == "$fallback" ]]; then
    fallback=""
  fi

  if ! tags="$(aws ecr describe-images \
    --region "$AWS_REGION" \
    --repository-name "$repository" \
    --query 'reverse(sort_by(imageDetails[?imageTags!=null], &imagePushedAt))[].imageTags[]' \
    --output text)"; then
    echo "::error::Failed to query ECR image tags for repository=${repository}" >&2
    exit 1
  fi

  if [[ -n "$tags" ]]; then
    latest_tag="$(printf '%s\n' "$tags" | tr '\t' '\n' | awk -v pfx="$prefix" '
      NF == 0 { next }
      pfx == "" { print; exit }
      index($0, pfx) == 1 { print; exit }
    ')"
  fi

  if [[ -n "$latest_tag" ]]; then
    printf '%s\n' "$latest_tag"
    return
  fi

  if [[ -n "$fallback" ]]; then
    printf '%s\n' "$fallback"
    return
  fi

  echo "::error::No image tag found in ECR repository=${repository} prefix=${prefix}" >&2
  exit 1
}

require_env AWS_REGION
require_env ADMIN_WEB_REPOSITORY
require_env API_SERVER_REPOSITORY

counseling_analytics_repository="${COUNSELING_ANALYTICS_REPOSITORY:-${COUNSELING_ANALYTICS_SOURCE_REPO:-}}"
if [[ -z "$counseling_analytics_repository" ]]; then
  echo "::error::COUNSELING_ANALYTICS_REPOSITORY or COUNSELING_ANALYTICS_SOURCE_REPO is required." >&2
  exit 1
fi

admin_web_image_tag="$(latest_ecr_tag_for_prefix "${ADMIN_WEB_REPOSITORY}" "" "latest")"
admin_api_image_tag="$(latest_ecr_tag_for_prefix "${API_SERVER_REPOSITORY}" "admin-" "latest")"
customer_api_image_tag="$(latest_ecr_tag_for_prefix "${API_SERVER_REPOSITORY}" "customer-" "latest")"
recommendation_realtime_image_tag="$(latest_ecr_tag_for_prefix "${counseling_analytics_repository}" "intelligence-server-" "latest")"

emit_var ADMIN_WEB_IMAGE_TAG "$admin_web_image_tag"
emit_var ADMIN_API_IMAGE_TAG "$admin_api_image_tag"
emit_var CUSTOMER_API_IMAGE_TAG "$customer_api_image_tag"
emit_var RECOMMENDATION_REALTIME_IMAGE_TAG "$recommendation_realtime_image_tag"
