# Phase 28 — 立体地形テンプレート

- battle/elite/exploration/boss 各10種類、計40個の新しい Sponge schematic v2 テンプレートを `src/main/resources/test-schematics/` に追加。
- 幅・奥行きはテンプレートごとに約55～107ブロックの範囲で異なる。高さ22、起伏0～8ブロック。
- テンプレートごとに10種の床材テーマ、複数の丘・段丘・障害物・照明を持つ。
- 起動時に `plugins/AbyssDungeon/schematics/<type>/terrain_XX.schem` と対応する `.yml` を不足分のみ配置。
- 戦闘系は新しいterrainテンプレートから選択し、貼付後に従来のプラグイン装飾処理を実行。
- 休憩・宝箱・イベントのコンパクト部屋は維持。

## 注意

- 既存テンプレートは削除せず、新テンプレートを優先する。
- Sponge schematic v2 は自作生成したため、WorldEdit 7.4.6 での読み込み・貼付は未検証。
- 元の schem の Offset と WorldEdit clipboard origin の解釈差によりスポーン位置がずれる可能性あり。テスト用サーバーで検証すること。
- 高低差により一部モブの経路探索が困難になる可能性がある。階段・通路は実機で確認すること。
