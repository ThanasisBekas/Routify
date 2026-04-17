#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────
# fix-git-email.sh
# Rewrites Git history to replace an old author/committer email
# with a correct one, then force-pushes all branches and tags.
# Also sets the correct email for future commits in the repo.
# ─────────────────────────────────────────────────────────────

usage() {
  echo "Usage: $0 <old-email> <correct-email>"
  echo ""
  echo "Example:"
  echo "  $0 old@example.com correct@example.com"
  exit 1
}

if [ $# -ne 2 ]; then
  usage
fi

OLD_EMAIL="$1"
CORRECT_EMAIL="$2"

# Ensure we're inside a git repo
if ! git rev-parse --is-inside-work-tree &>/dev/null; then
  echo "❌ Error: Not a git repository."
  exit 1
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Old email:     $OLD_EMAIL"
echo "  Correct email: $CORRECT_EMAIL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Check for uncommitted changes
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "⚠️  Stashing uncommitted changes..."
  git stash --include-untracked
  STASHED=true
else
  STASHED=false
fi

# Rewrite history
echo "🔄 Rewriting commit history..."
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch --env-filter "
if [ \"\$GIT_COMMITTER_EMAIL\" = \"$OLD_EMAIL\" ]; then
    export GIT_COMMITTER_EMAIL=\"$CORRECT_EMAIL\"
fi
if [ \"\$GIT_AUTHOR_EMAIL\" = \"$OLD_EMAIL\" ]; then
    export GIT_AUTHOR_EMAIL=\"$CORRECT_EMAIL\"
fi
" --tag-name-filter cat -- --branches --tags

# Clean up backup refs
echo "🧹 Cleaning up backup refs..."
git for-each-ref --format='delete %(refname)' refs/original | git update-ref --stdin 2>/dev/null || true

# Restore stash if needed
if [ "$STASHED" = true ]; then
  echo "📦 Restoring stashed changes..."
  git stash pop
fi

# Verify no old email remains
REMAINING=$(git log --all --format='%ae%n%ce' | grep -c "^${OLD_EMAIL}$" || true)
if [ "$REMAINING" -eq 0 ]; then
  echo "✅ Rewrite successful — no commits with '$OLD_EMAIL' remain."
else
  echo "⚠️  Warning: $REMAINING references to '$OLD_EMAIL' still found."
fi

# Force push
read -rp "🚀 Force push all branches and tags to remote? [y/N] " CONFIRM
if [[ "$CONFIRM" =~ ^[Yy]$ ]]; then
  git push --force --all
  git push --force --tags
  echo "✅ Force push complete."
else
  echo "⏭️  Skipped push. Run manually:"
  echo "     git push --force --all && git push --force --tags"
fi

# Set correct email for future commits
git config user.email "$CORRECT_EMAIL"
echo "📧 Repo email set to '$CORRECT_EMAIL' for future commits."
echo ""
echo "🎉 Done!"

