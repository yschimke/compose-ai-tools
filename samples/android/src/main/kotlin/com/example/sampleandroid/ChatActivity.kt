package com.example.sampleandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A chat composer, edge to edge: the message field sits at the bottom and rides up above the soft
 * keyboard (`imePadding()`), which is what the simulator's on-screen keyboard is shown with. Not in
 * the launcher; started with `startActivity`.
 */
class ChatActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val messages = remember { mutableStateListOf("Are we still on for lunch?") }
          var draft by remember { mutableStateOf("") }
          Column(
            modifier =
              Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
          ) {
            Text(
              "Ada",
              style = MaterialTheme.typography.titleLarge,
              modifier = Modifier.padding(16.dp),
            )
            LazyColumn(
              modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(messages) { message ->
                Card { Text(message, modifier = Modifier.padding(12.dp)) }
              }
            }
            Row(
              modifier = Modifier.fillMaxWidth().padding(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
              )
              Button(
                onClick = {
                  if (draft.isNotBlank()) messages.add(draft)
                  draft = ""
                },
                modifier = Modifier.padding(start = 8.dp),
              ) {
                Text("Send")
              }
            }
          }
        }
      }
    }
  }
}
