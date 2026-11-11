#!/usr/bin/env bash
set -euo pipefail

release_tag_pattern='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

if ! [[ "$TAG_NAME" =~ $release_tag_pattern ]]; then
  echo "::error::Tag $TAG_NAME is not a release version. Use MAJOR.MINOR.PATCH, for example 1.7.0."
  exit 1
fi

previous_tag=$(git tag --list | grep -E "$release_tag_pattern" | grep -vxF "$TAG_NAME" | sort -V | tail -n 1 || true)

if [ -n "$previous_tag" ]; then
  IFS=. read -r major minor patch <<<"$previous_tag"
  next_patch="$major.$minor.$((patch + 1))"
  next_minor="$major.$((minor + 1)).0"
  next_major="$((major + 1)).0.0"
  if [ "$TAG_NAME" != "$next_patch" ] && [ "$TAG_NAME" != "$next_minor" ] && [ "$TAG_NAME" != "$next_major" ]; then
    echo "::error::Tag $TAG_NAME does not follow $previous_tag. Use $next_patch, $next_minor or $next_major."
    exit 1
  fi
fi

if ! git merge-base --is-ancestor "$GITHUB_SHA" origin/main; then
  echo "::error::Tag $TAG_NAME points to a commit that is not on main."
  exit 1
fi

echo "Tag $TAG_NAME is a valid release after ${previous_tag:-no previous release}."
