---
inclusion: auto
---

# Spec File Location

When creating or updating spec files (requirements.md, design.md, tasks.md), always store them in the workspace directory under `.kiro/specs/{feature_name}/`, not in the user-level `~/.kiro/specs/` directory.

This ensures spec files are committed to version control alongside the project source code.
