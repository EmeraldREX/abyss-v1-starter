# 第21段階 アイテム定義・確認用一覧（部分実装）

Wikiから確認できた名称を元に、固定値の装備定義を追加。合計 74 種（武器26・頭防具28・アクセサリー20）。

- `src/main/resources/phase21_items.tsv` が数値マスター。Excel確認表と同じ数値。
- `AbyssEquipment.definition(id)` が新規アイテムIDを解決し、装備・倉庫表示・既存の攻撃/防御/魔力/MP計算に接続。
- **新規アイテムのドロップ抽選・獲得手段は未接続**。特殊効果も未実装。最大HPは既存コード同様「表示のみ」で実ステータス未反映。魔法防御も未接続。
- Wiki全件ではなく、ページ上で名称と係数を確認できた一部のみ。残りは未収録。
- SQLiteの既存スキーマを変更していない。
- Gradleビルド・Paper実機テスト未実施。

出典: https://dhou1magus.github.io/dqmvi-wiki/items/weapons /armor /accessories
