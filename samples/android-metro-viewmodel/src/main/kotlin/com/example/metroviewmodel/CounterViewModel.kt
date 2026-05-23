package com.example.metroviewmodel

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A trivial constructor-injected collaborator so the VM isn't just holding
 * its own state — it depends on something the graph has to wire up. Any
 * `@Inject` class without an explicit scope is unscoped (a new instance
 * per request), which is fine for a stateless seed provider.
 */
@Inject
class CounterRepository {
  fun initialCount(): Int = 7
}

@Inject
@ViewModelKey(CounterViewModel::class)
@ContributesIntoMap(AppScope::class)
class CounterViewModel(private val repository: CounterRepository) : ViewModel() {
  private val _count = MutableStateFlow(repository.initialCount())
  val count: StateFlow<Int> = _count.asStateFlow()

  fun increment() {
    _count.value++
  }

  fun decrement() {
    _count.value--
  }
}
