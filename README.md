# WorkWave


Мобильное Android‑приложение для учёта рабочего времени сотрудников с использованием QR‑кодов и ролями сотрудник / HR‑менеджер.

- Сотрудник:
  - логин по почте и паролю;
  - запуск и завершение смены по QR‑коду;
  - просмотр своих смен в календаре;
  - просмотр и редактирование своего профиля.
- HR‑менеджер:
  - полный список сотрудников;
  - просмотр / редактирование профилей всех сотрудников;
  - добавление/скрытие сотрудников в списке;
  - просмотр смен всех сотрудников в календаре.

Поддерживает 3 языка интерфейса: **русский**, **английский**, **корейский**.

---

## Используемые технологии

- **Язык**: Kotlin
- **UI**:
  - Android View System
  - Material Components (`MaterialToolbar`, `MaterialButton`, `TextInputLayout`, `BottomNavigationView` и др.)
  - `RecyclerView` + `ListAdapter` + `DiffUtil`
  - `CalendarView`, `Chronometer`, `TextClock`
- **Архитектура и данные**:
  - Room (`AppDatabase`, DAO, Entity)
  - Kotlin coroutines (`lifecycleScope`, `withContext(Dispatchers.IO)`)
  - SharedPreferences (настройки темы, языка, формата даты/времени)
- **Авторизация и безопасность**:
  - Локальная аутентификация через e‑mail + пароль
  - Собственный `PasswordHasher` (хеширование пароля + соль, хранение в Room)
- **Сеть / облако**:
  - Firebase Firestore (`Firebase.firestore`)
  - Синхронизация пользователей и сотрудников (`FirebaseEmployees`)
- **QR‑код**:
  - Google ML Kit Code Scanner (`GmsBarcodeScanning`, `GmsBarcodeScannerOptions`)
- **Локализация**:
  - `LocaleUtils` + `BaseActivity` с переопределением `attachBaseContext`
  - ресурсные директории `values`, `values-en`, `values-ko` и `strings.xml`

---

## Структура

```text
WorkWave
├── build.gradle.kts
├── settings.gradle.kts
└── app
    ├── build.gradle.kts
    ├── google-services.json
    └── src
        └── main
            ├── AndroidManifest.xml
            ├── java
            │   └── com
            │       └── workwave
            │           └── workwave
            │               ├── data
            │               │   ├── AppDatabase.kt
            │               │   ├── EmployeeDao.kt
            │               │   ├── EmployeeEntity.kt
            │               │   ├── UserDao.kt
            │               │   ├── UserEntity.kt
            │               │   ├── WorkSessionDao.kt
            │               │   ├── WorkSessionEntity.kt
            │               │   ├── SessionWithUserEmail.kt
            │               │   └── UserWithNames.kt
            │               ├── firebase
            │               │   └── FirebaseEmployees.kt
            │               ├── security
            │               │   └── PasswordHasher.kt
            │               ├── ui
            │               │   ├── BaseActivity.kt
            │               │   ├── HomeActivity.kt
            │               │   ├── LoginActivity.kt
            │               │   ├── MainActivity.kt
            │               │   ├── ProfileActivity.kt
            │               │   ├── RegisterActivity.kt
            │               │   └── SettingsActivity.kt
            │               └── util
            │                   ├── LocaleUtils.kt
            │                   └── ThemeUtils.kt
            └── res
                ├── layout
                │   ├── activity_home.xml
                │   ├── activity_login.xml
                │   ├── activity_main.xml
                │   ├── activity_profile.xml
                │   ├── activity_register.xml
                │   ├── activity_settings.xml
                │   ├── item_employee.xml
                │   ├── item_session.xml
                │   ├── view_calendar_content.xml
                │   └── view_employees_content.xml
                ├── menu
                │   ├── menu_bottom_home.xml
                │   ├── menu_profile.xml
                │   └── menu_top_home.xml
                ├── drawable
                │   ├── back.png
                │   ├── bg_block_white_rounded.xml
                │   ├── bg_today.xml
                │   └── user_ava.png
                ├── values
                │   ├── strings.xml
                │   ├── colors.xml
                │   ├── styles.xml
                │   └── themes.xml
                ├── values-en
                │   └── strings.xml
                └── values-ko
                    └── strings.xml
