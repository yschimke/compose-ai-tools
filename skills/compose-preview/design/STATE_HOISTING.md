# Designing composables for previewability

`@Preview` only calls composables with zero arguments (or all-default). That
rules out anything taking a `ViewModel`, repository, or DI-injected service.
The standard fix is **state hoisting** — split each screen into two layers:

```kotlin
// Stateful wrapper: wires runtime dependencies. Not previewable.
@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    HomeScreen(state = state, onRefresh = viewModel::refresh)
}

// Stateless UI: pure function of state + callbacks. Previewable with
// hand-rolled fixtures — no mocks, no test dispatchers, no DI.
@Composable
fun HomeScreen(state: HomeState, onRefresh: () -> Unit) { /* … */ }

@Preview @Composable fun HomeScreen_loaded() =
    HomeScreen(HomeState.Loaded(items = sampleItems), onRefresh = {})

@Preview @Composable fun HomeScreen_empty() =
    HomeScreen(HomeState.Empty, onRefresh = {})

@Preview @Composable fun HomeScreen_error() =
    HomeScreen(HomeState.Error("Network unavailable"), onRefresh = {})
```

Every visual state is a fixture — the state is data, constructed inline.
This is also the foundation for testing UI without standing up business
logic: the same stateless composable that a preview renders is the one a
screenshot test or Compose UI test exercises.

**Agent guidance:** if you're asked to iterate on a composable that accepts
a ViewModel, repository, or injected dependency, **first propose extracting
a stateless inner composable and preview that instead.** Rendering the
stateful wrapper either fails outright or produces a misleading
empty/loading frame that doesn't exercise the UI. The one-time extraction
cost unlocks the fast `compose-preview` iteration loop for every future
change on that screen.
