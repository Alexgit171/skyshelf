# Перенос SkyShelf в Figma

Цель — получить редактируемые слои всех экранов, доработать дизайн в Figma и затем передать изменённые фреймы обратно для реализации.

## Подготовка проекта

1. Запустите `start.bat` и войдите демо-аккаунтом.
2. Откройте `http://localhost:5500/design-system.html` — это отдельный UI inventory: цвета, типографика, кнопки, поля, карточки, файлы, статусы и spacing.
3. Установите в Windows шрифты из `frontend/assets`:
   - `AtypDisplay-Semibold.ttf` — заголовки;
   - `SF-Pro-Display-Regular.otf` — текст и интерфейс.
4. Создайте Figma-файл `SkyShelf · Product UI` со страницами Foundations, Components, Desktop, Public и Mobile.

## Вариант A — официальный Figma Code to Canvas

Используйте официальный Figma Chrome extension и функцию захвата кодовых экранов:

- инструкция: https://help.figma.com/hc/en-us/articles/40826832449303-Turn-coded-screens-into-editable-design-layers
- расширение: https://help.figma.com/hc/en-us/articles/9067379354135-Figma-Chrome-extension

Порядок:

1. Откройте нужный localhost-экран в Chrome.
2. На главной сначала прокрутите страницу до конца, затем вернитесь наверх: так активируются reveal-анимации.
3. Запустите захват whole page или конкретного элемента.
4. Перейдите в Figma, кликните canvas и вставьте содержимое буфера.
5. Переименуйте фрейм по таблице ниже.
6. Проверьте шрифты, Auto Layout, радиусы и привязку цветов.

Figma переносит страницу как редактируемые слои и умеет сопоставлять CSS variables с переменными файла. В SkyShelf используются читаемые токены `--bg`, `--surface`, `--raised`, `--border`, `--text`, `--muted`, `--accent`, поэтому dev-server подходит для захвата лучше минифицированной production-сборки.

Ограничение: сложные scroll-анимации могут переноситься неидеально, а кодовые компоненты не становятся автоматически готовыми Figma-компонентами. После импорта соберите повторяющиеся элементы вручную в component sets. Доступность отдельных возможностей расширения может зависеть от тарифа Figma.

## Вариант B — html.to.design для localhost/private pages

Если официальный способ недоступен или плохо захватывает локальные авторизованные страницы:

- расширение: https://html.to.design/docs/extension-tab/
- private/local workflow: https://html.to.design/docs/import-private-page/

Расширение захватывает localhost и страницы после входа. Результат можно передать в plugin напрямую либо сохранить локальный `.h2d`, а затем импортировать в Figma. Этот вариант особенно удобен для кабинета, где нужна действующая сессия.

## Карта экранов

| Фрейм Figma | URL / действие | Рекомендуемый viewport |
| --- | --- | --- |
| 00 · UI inventory | `/design-system.html` | 1440 × full page |
| 01 · Landing | `/#home` | 1440 × full page |
| 02 · Login | `/#login` | 1440 × 960 |
| 03 · Register | `/#register` | 1440 × 960 |
| 04 · My files | войти → `/#files` | 1440 × 960 |
| 05 · Favorites | `/#favorites` | 1440 × 960 |
| 06 · Projects | `/#projects` | 1440 × 960 |
| 07 · Project workspace | открыть «SkyShelf · СПО» | 1440 × full page |
| 08 · Activity | `/#activity` | 1440 × 960 |
| 09 · Security | `/#security` | 1440 × full page |
| 10 · Settings | `/#settings` | 1440 × 960 |
| 11 · Admin | войти admin → `/#admin` | 1440 × full page |
| 12 · File share | создать ссылку → открыть `/#share/{token}` | 1440 × 960 |
| 13 · Teacher card | создать сдачу → `/#submit/{token}` | 1440 × full page |
| M01–M10 · Mobile | те же основные экраны | 390 × 844 |

Исходные токены публичных ссылок показываются только при создании. Для повторного захвата проще создать новую ссылку.

## Переменные Figma

Создайте collection `SkyShelf / Color`:

| Переменная | Dark | Light |
| --- | --- | --- |
| `bg` | `#081426` | `#F2F5FB` |
| `surface` | `#0D1C32` | `#FFFFFF` |
| `raised` | `#12233E` | `#EAF0FA` |
| `border` | `#23334B` | `#DBE3EF` |
| `text` | `#F4F7FE` | `#11223E` |
| `muted` | `#91A2BC` | `#5B6C87` |
| `accent/blue` | `#528AFF` | `#2864DB` |
| `accent/violet` | `#A282FF` | `#A282FF` |
| `accent/mint` | `#45C4AD` | `#45C4AD` |
| `danger` | `#FF8995` | `#C33347` |

Number variables: `radius/8`, `radius/10`, `radius/14`, `radius/18`, `radius/30`; spacing 4, 8, 12, 16, 24, 32, 48, 64.

## Компоненты после импорта

Соберите как Figma components:

- Button: primary / secondary / glass / danger; default / hover / disabled;
- Icon button;
- Badge: neutral / active / final / warning;
- Input / Select / Search;
- Sidebar item;
- File row и File type;
- Folder card;
- Project card и Project hero;
- Task row;
- Member row;
- Security status;
- Modal и Toast.

## Как вернуть дизайн в разработку

1. Не переименовывайте смысловые фреймы после согласования.
2. Для каждого изменённого экрана добавьте короткую заметку: что изменено и что интерактивно.
3. Зафиксируйте состояния desktop 1440 и mobile 390, а для сложных элементов — hover/open/error.
4. Пришлите ссылку Figma с доступом на просмотр или экспортированные фреймы PNG/PDF вместе с токенами.
5. Изменения будут сопоставлены с существующими HTML/CSS-компонентами; бизнес-логика и API останутся без переписывания, если дизайн не меняет сценарий.

Анимации лучше описывать отдельно: trigger, начальное/конечное состояние, duration и easing. Параллакс главной уже реализован кодом и не обязан буквально переноситься в статичный Figma-фрейм.
