# Тестовый кластер YDB (3 ноды) + Java-приложение

Локальный тестовый стенд: кластер [YDB](https://ydb.tech) из трёх статических нод
(отказоустойчивость mirror-3-dc), база данных и простое Java-приложение,
работающее с ней через JDBC. Всё поднимается одной командой через Docker Compose.

## Состав

| Компонент | Описание |
|---|---|
| `ydb-node-1..3` | Статические ноды YDB (образ `ydbplatform/local-ydb:26.3.1.19`), по одной ноде на «дата-центр». Данные — файловые pdisk по 8 ГБ × 3 на ноду в named volumes (`ydb-node-N-data`), переживают перезапуск контейнеров |
| `ydb-init` | Одноразовый сервис: ждёт готовности кластера, инициализирует blob storage и создаёт базу `/local/testdb` |
| `ydb-dynnode` | Динамическая (tenant) нода для базы `/local/testdb` — статические ноды обслуживают только корневой домен `/local` |
| `app` | Тестовое Java-приложение (JDBC): создаёт таблицу `series`, пишет и читает строки |

Конфигурация кластера — `ydb/config.yaml` (адаптация официального примера
`mirror-3dc-3-nodes.yaml` из репозитория [ydb-platform/ydb](https://github.com/ydb-platform/ydb/tree/main/ydb/deploy/yaml_config_examples)).

## Требования

- Docker 25+ с Docker Compose v2 (проверено на Docker Desktop для Windows).
- Достаточно ~10 ГБ свободного места (sparse-файлы pdisk растут по мере записи данных).

## Запуск

```bash
docker compose up -d --build
```

Первый подъём занимает 1–3 минуты: скачивание образа, сборка Java-приложения,
инициализация кластера (нодам нужно договориться о кворуме статической группы).

Проверка состояния:

```bash
docker compose ps                 # все 3 ноды — healthy, app и ydb-init — Exited (0)
docker compose logs ydb-init      # "Creating database /local/testdb..." — база создана
docker compose logs app           # таблица создана, строки записаны/прочитаны, SUCCESS
```

Пример вывода приложения:

```
Connecting to YDB: jdbc:ydb:grpc://ydb-node-1:2136/local/testdb
Table 'series' is ready.
3 rows upserted.
Rows in 'series':
  series_id=1  title=IT Crowd
  series_id=2  title=Silicon Valley
  series_id=3  title=Mr. Robot
SUCCESS
```

## Повторный запуск приложения

```bash
docker compose run --rm app
```

## Полезные команды

Веб-интерфейс (monitoring):
- нода 1: http://localhost:8765
- нода 2: http://localhost:8766
- нода 3: http://localhost:8767
- динамическая нода: http://localhost:8768

SQL-запрос из контейнера ноды (CLI `ydb` уже внутри образа):

```bash
docker compose exec ydb-node-1 /ydb --endpoint grpc://localhost:2136 --database /local/testdb yql -s "SELECT * FROM series ORDER BY series_id"
```

Проверка того, что кластер действительно трёхузловой:

```bash
docker compose exec ydb-node-1 /ydb --endpoint grpc://localhost:2136 --database /local discovery list
```

## Проверка отказоустойчивости

mirror-3-dc переживает отказ одной ноды:

```bash
docker compose stop ydb-node-3
docker compose run --rm app          # по-прежнему SUCCESS
docker compose start ydb-node-3
```

## Остановка

```bash
docker compose stop      # контейнеры остановлены, данные (pdisk) сохранены в volumes
docker compose down      # контейнеры удалены, данные сохранены в volumes
docker compose down -v   # полная очистка, включая данные кластера
```

После `down -v` следующий `up` создаст кластер заново, и `ydb-init` пересоздаст базу.

## Публикация на GitHub

Репозиторий уже инициализирован локально. Для публикации создайте репозиторий на
github.com и выполните:

```bash
git remote add origin git@github.com:<your-account>/ydbClusterTest.git
git push -u origin main
```

## Как это устроено

- Штатный entrypoint образа `local-ydb` разворачивает только одну ноду, поэтому он
  переопределён: каждый контейнер запускает `ydbd server --yaml-config ... --node N`
  с общим конфигом кластера (`ydb/config.yaml`, формат V1 по мотивам официальной
  документации «Deploying a Cluster Using Configuration V1»). Корневой домен в конфиге
  назван `local`.
- Встроенный HEALTHCHECK образа (DDL-проба по базе `/local`) не проходит до разовой
  инициализации blob storage, поэтому в compose он переопределён на простую проверку
  готовности (`select 1`); у динамической ноды отключён (она не обслуживает `/local`).
- pdisk-файлы (8 ГБ × 3 на ноду, sparse) создаются командой запуска контейнера при
  первом старте и лежат в named volumes.
- Сервис `ydb-init` при первом подъёме выполняет разовую инициализацию:
  `ydbd admin blobstorage config init` (регистрирует pdisk'и в BS-контроллере) и
  `ydbd admin database /local/testdb create ssd:1` (база с одним storage group).
  При повторных подъёмах он видит существующую базу и ничего не делает.
- Статические ноды обслуживают только корневой домен, поэтому для `/local/testdb`
  запущена динамическая нода (`--tenant /local/testdb --node-broker ...`).
- Java-приложение использует официальный JDBC-драйвер
  [tech.ydb.jdbc:ydb-jdbc-driver-shaded](https://github.com/ydb-platform/ydb-jdbc-driver).
