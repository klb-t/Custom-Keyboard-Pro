# State Ownership

- **KeyboardDocument**: Authoritative owner of layouts, panels, elements, and styles.
- **Compose State**: Derived rendering state. Not a source of truth for business logic.
- **SharedPreferences**: Deprecated for diagnostic logs. Should only be used for tiny boolean flags/settings (e.g., config toggle).
- **Diagnostics**: Ring buffer and persistent trace sinks own logging state asynchronously.
- **Selection**: `InputConnection` handles native selection state, `SelectionContextSource` abstracts it.
