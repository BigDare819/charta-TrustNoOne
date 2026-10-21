# MyAvalon — 项目长期要点

## 构建
- 构建用 `./gradlew build --offline`（**离线**）。直连会卡在解析 `fabric-loom:1.11-SNAPSHOT` 上。
- 产物：`build/libs/myavalon-fabric-1.21.1-1.0.0.jar`；部署到 PCL2 实例用 `gradlew deploy`。
- 不必 `runClient` 验证，编译通过 + 核对产物即可（用户偏好）。

## Charta 集成（关键坑）
- `IrisCompatPresent` mixin 把 `IrisCompat.isPresent()` 强制 true → Charta 走"全彩/直采"路径：
  `ChartaGuiGraphics.blitCard` 用 `card_argb`，卡面纹理是普通 RGBA PNG（`FullColorCards` 在
  `generateImages` 尾部把 `assets/myavalon/fullcard/**.png` 绑到卡面 id 上）。
- `charta:card` 着色器按**红色通道**查 64 色调色板（`charta:palette.glsl`），只适用于 `.mccard` 调色板格式；
  自己画的 GUI PNG（team_flag / crown / mission_marker 等）不要走它，用 `card_argb`。
- 卡片的"光晕"是第二遍 pass：把纹理画进 glow render target → 模糊 → 以 `GL_ONE,GL_ONE` **整屏相加**。
  **绝不要把自己的图当自己的 glow 纹理**，否则会比原图亮，而且整屏相加会"洗"到先画的文字/名牌上。
- Charta 源码（含 assets/shaders）：`~/.m2/repository/dev/lucaargolo/charta-common-1.21.1/1.2.5/*-sources.jar`。

## 架构约定
- `AvalonScreen` 是 640x360 设计帧 + 均匀缩放（`AvalonFrame`），布局在 `AvalonLayout`，F9 可编辑并写
  `config/myavalon-layout.properties`。
- 秘密信息只经 `AvalonMenu`（镜像容器数据），屏幕永远不读 `game.roleAt`。
- 阶段：DEALING → PROPOSE → VOTE → QUEST →（PROPOSE… | ASSASSIN | OVER）。投票是**同时投**（`beginVoting`），
  提议/刺杀/任务出牌仍是"单人回合"（`awaitAction`/`awaitCard` + `currentPlayer`）。
