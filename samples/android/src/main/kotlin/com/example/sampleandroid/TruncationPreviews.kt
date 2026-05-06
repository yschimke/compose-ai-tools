package com.example.sampleandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Per-check fixtures for the `text/strings` v2 truncation fields. Each preview isolates one of
 * `didOverflowWidth`, `didOverflowHeight`, the `maxLines` cap, and `overflow=Ellipsis` so a
 * localisation reviewer can eyeball the rendered PNG against the data-product fields. The
 * matching `TextStringsTruncationTest` exercises the same composable bodies through the
 * producer and asserts each preview's expected check fires.
 */
private const val LongGerman =
  "Vollstaendig und unwiderruflich abgeschlossen, mit zusaetzlichen Erlaeuterungen"

@Preview(showBackground = true, widthDp = 80, heightDp = 32)
@Composable
fun TruncatedWidthNoWrapPreview() {
  Box(modifier = Modifier.size(width = 80.dp, height = 32.dp).background(Color.White)) {
    Text(
      text = LongGerman,
      softWrap = false,
      overflow = TextOverflow.Clip,
      fontSize = 14.sp,
    )
  }
}

@Preview(showBackground = true, widthDp = 200, heightDp = 20)
@Composable
fun TruncatedHeightClipPreview() {
  Box(modifier = Modifier.width(200.dp).height(20.dp).background(Color.White)) {
    Text(text = LongGerman, overflow = TextOverflow.Clip, fontSize = 14.sp)
  }
}

@Preview(showBackground = true, widthDp = 200, heightDp = 80)
@Composable
fun TruncatedMaxLinesEllipsisPreview() {
  Box(modifier = Modifier.size(width = 200.dp, height = 80.dp).background(Color.White)) {
    Text(
      text = LongGerman,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      fontSize = 14.sp,
    )
  }
}

@Preview(showBackground = true, widthDp = 240, heightDp = 32)
@Composable
fun FitsInBoundsPreview() {
  Box(modifier = Modifier.size(width = 240.dp, height = 32.dp).background(Color.White)) {
    Text(text = "Hello", fontSize = 14.sp)
  }
}
