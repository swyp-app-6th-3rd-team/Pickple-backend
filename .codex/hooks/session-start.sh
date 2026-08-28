#!/bin/sh

# SessionStart must be read-only and best-effort. It emits plain text that Codex
# adds to the session context.

event_json=$(cat 2>/dev/null || true)
source_name=$(printf '%s' "$event_json" | sed -n 's/.*"source"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | sed -n '1p')
if [ -z "$source_name" ]; then
    source_name=unknown
fi

script_dir=$(CDPATH= cd -P "$(dirname "$0")" 2>/dev/null && pwd)
repo_root=$(CDPATH= cd -P "$script_dir/../.." 2>/dev/null && pwd)

branch=$(git -c "safe.directory=$repo_root" -c core.quotepath=false -C "$repo_root" branch --show-current 2>/dev/null || true)
if [ -z "$branch" ]; then
    short_head=$(git -c "safe.directory=$repo_root" -C "$repo_root" rev-parse --short HEAD 2>/dev/null || true)
    if [ -n "$short_head" ]; then
        branch="detached@$short_head"
    else
        branch=unknown
    fi
fi

changes=$(git -c "safe.directory=$repo_root" -c core.quotepath=false -C "$repo_root" status --short 2>/dev/null || true)
if [ -z "$changes" ]; then
    working_tree=clean
else
    change_count=$(printf '%s\n' "$changes" | awk 'NF { count++ } END { print count + 0 }')
    working_tree="$change_count changed path(s)"
fi

printf '%s\n' \
    'Pickple backend repository context:' \
    "- Session source: $source_name" \
    "- Git root: $repo_root" \
    "- Current branch: $branch" \
    "- Working tree: $working_tree" \
    '- Read and follow AGENTS.md before changing files.' \
    '- Preserve existing user changes and keep work inside the requested scope.' \
    '- Do not commit, push, open or edit a PR, or deploy unless the user explicitly asks.' \
    '- This is a Java 25 / Spring Boot / Gradle project; use the OS-appropriate Gradle Wrapper and report the exact validation scope.' \
    '- Use the repository resolve-problem skill for non-obvious bugs, performance issues, or multi-component diagnosis.' \
    '- Report the current branch after code work.'

exit 0
