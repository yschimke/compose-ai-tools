package com.example.metroviewmodel

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import kotlin.reflect.KClass

/**
 * App-scoped Metro graph. Extending [ViewModelGraph] gives us a
 * `metroViewModelFactory: MetroViewModelFactory` accessor plus the three
 * multibindings the factory needs (standard, assisted, manual-assisted).
 * The sample only uses the standard map — the other two stay empty.
 */
@DependencyGraph(AppScope::class) interface AppGraph : ViewModelGraph

/**
 * Binds [MetroViewModelFactory] in the graph by exposing the multibindings
 * Metro contributes for it. Lifted verbatim from the upstream
 * `compose-viewmodels` sample.
 */
@ContributesBinding(AppScope::class)
@Inject
class InjectedViewModelFactory(
  override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>,
  override val assistedFactoryProviders:
    Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>>,
  override val manualAssistedFactoryProviders:
    Map<KClass<out ManualViewModelAssistedFactory>, Provider<ManualViewModelAssistedFactory>>,
) : MetroViewModelFactory()
