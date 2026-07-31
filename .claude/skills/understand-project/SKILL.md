---
name: understand-project
description: Ground a feature or fix in existing code before planning or building — find a similar pattern, identify reusable assets, map the touch surface. Internal helper invoked by feat / fix (both modes) — not meant to be run on its own.
disable-model-invocation: true
---

Ground every plan and implementation in code that already exists. A plan that says "follows the same pattern as `src/itkach/aard2/lookup/LookupFragment.java`" beats one describing an abstraction in the abstract — the executor gets low cognitive load and a working reference. This is the _method_; the calling skill says what to build.

## Steps

1. **Learn the layout that applies.** Java sources are under `src/` (not `src/main/java` — see the `sourceSets` block in `build.gradle`), package `itkach.aard2`: `article/` (WebView article display), `lookup/` (search), `dictionaries/` (dictionary list, folder manager, scanner), `dictionary/` + `dictionary/mdict/` + `dictionary/stardict/` (format parsing — the pure-logic core), `slob/` (`SlobServer`), `descriptor/` (`DescriptorStore` persistence), `prefs/` (`AppPrefs`, `SettingsFragment`), `widget/` (custom views), `audio/`, `utils/`; top level holds `MainActivity`, `Application`, `SlobHelper` and the list adapters. Resources are in `res/layout/`, `res/menu/`, `res/values/strings.xml`; assets (WebView JS) in `assets/`.
2. **Find a similar existing implementation.** Glob/Grep for an `Activity`, `Fragment`, `RecyclerView` adapter, `ViewModel`, or parser that solves a comparable problem. Read 1-2 end-to-end so you can point at them by path.
3. **Identify reusable assets** — base classes (`BaseDescriptorList`, `BaseListFragment`, `BaseDescriptor`), helpers in `utils/` (`Utils`, `ThreadUtils`, `ClipboardUtils`, `StyleJsUtils`), custom views in `widget/`, preference access via `prefs/AppPrefs`, persistence via `descriptor/DescriptorStore`, and existing `res/layout` includes. Reuse these by name rather than inventing new ones.
4. **Map the touch surface** — every Java class, layout, menu, `res/values/strings.xml` key, `AndroidManifest.template.xml` entry, and `proguard-rules.pro` keep rule that needs to change. Trace data flow and callers so nothing is missed.
5. **Check third-party libraries** — when a library is involved (AndroidX, Material 3, `androidx.webkit`, Jackson, jspeex), use Context7 for version-matched docs before assuming an API. For slob/aard2 internals, read this repo's source and the `slobj` submodule instead.

## Bias

Pick the simplest, cleanest solution: reuse existing patterns/base classes/helpers, fewest files touched, smallest new surface. If a clever approach and a boring approach reach the same outcome, choose the boring one.
