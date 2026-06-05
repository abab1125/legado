# CLAUDE.md — 工作规范

## Git 工作流

- **必须使用 git 进行本地管理**：每次修改完成后必须 `git add` + `git commit`，不能裸改文件不提交
- **绝对不允许 push**：在得到用户明确许可之前，只做本地 commit，绝不 `git push`
- **commit message 要写清楚**：说明改了什么，格式如 `fix: 修复xxx` / `feat: 新增xxx` / `docs: 更新xxx`

## Push 前检查清单

每次用户要求 push 之前，必须先执行以下步骤：

1. **读取 `notes/to-do-list.md`**，了解当前项目待办事项的全貌
2. **根据本次开发内容**，更新 `to-do-list.md`：
   - 新完成的功能：从未完成移到已完成，或标注 ✅
   - 新发现的待办：添加到对应优先级分组
   - 状态变更：更新状态列
3. **Commit to-do-list 的更新**
4. **确认用户允许后**，再执行 `git push`
