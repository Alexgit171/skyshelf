# SkyShelf Final 1.0 · 26.09.2026

## Главное обновление после MVP

- выразительная landing page со стеклянным облаком, параллаксом, reveal-анимациями и плавным курсором;
- полноценный student workspace: курс, преподаватель, дедлайн, оформление, чек‑лист и команда;
- история версий, заметки, авторы и восстановление;
- избранное и расширенная карточка файлов;
- итоговый файл, teacher card и ZIP-экспорт проекта;
- ссылки с паролем, сроком и лимитом скачиваний;
- AES‑256‑GCM, SHA‑256, Argon2id, TOTP-2FA и аудит;
- Security Center, Activity и расширенная admin-консоль;
- design-system page и инструкция переноса в Figma;
- обновлённые стартовые скрипты и полный комплект документации.

## Проверено

- исходники backend компилируются на Java 17;
- исполняемый JAR имеет версию 1.0.0;
- JavaScript проходит syntax check;
- расширенный HTTP smoke проходит целиком;
- локальный backup/restore подтверждён сравнением байтов.

Ограничения visual browser QA, Docker и публичного HTTPS честно зафиксированы в `docs/TESTING.md`.

## Контрольные суммы

```text
d27cfb67798ef30db8c2315f7ec8c0b756fa3b86fb9cb191647fc33bd9c4c8dd  backend/target/skyshelf.jar
27f796616cc8ab07c4e635541b904ebf09a8ccc7713c3ba18828a907c7994ec9  frontend/app.js
a9d1f5fe062c7f2c24457db62e3b0cbe5559f448d106aa0be3690f942f451ea3  frontend/style.css
```
