# Architecture
Modules: core-* (shared), engine-api (interfaces) <- engine-* (implementations), feature-* (UI logic), app (entry, DI wiring).
Rule: engines never depend on UI. features depend on engine-api only, never on engine-* directly. Only `app` wires implementations.
