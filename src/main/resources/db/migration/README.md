# Миграции

`V1__init.sql` — начальная схема, эталон, не редактируется.

Свои миграции добавляйте следующими номерами: `V2__...sql`, `V3__...sql` и т.д.
(стандартная схема именования Flyway: `V<версия>__<описание>.sql`).

## Конвенции, на которые опираются эталонные тесты

**ЛР2 (нормализация).** Данные клиента переезжают в таблицу `customers`, имя клиента —
в колонку `customers.full_name`. Из `orders` уходят `customer_full_name`,
`customer_address`, `customer_phone`, из `order_items` — `product_name`, `product_price`.

**ЛР3 (expand-contract).** Разбиение `customers.full_name` на `first_name`/`last_name`:

- в описании каждой миграции ЛР3 есть `lab3`, например
  `V7__lab3_expand_add_name_columns.sql`, `V8__lab3_backfill_names.sql`,
  `V9__lab3_contract_drop_full_name.sql`;
- миграций ЛР3 не меньше двух, все они идут после миграций ЛР2;
- последняя миграция в цепочке — contract-шаг (удаление `full_name`); все предыдущие
  шаги ЛР3 не должны ломать чтение `full_name` и должны заполнить новые колонки
  для уже существующих клиентов.
