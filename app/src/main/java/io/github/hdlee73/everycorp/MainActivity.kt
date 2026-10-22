package io.github.hdlee73.everycorp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.update.UpdateWorker
import io.github.hdlee73.everycorp.ui.theme.EveryCorpTheme

/** Step 3 시점의 임시 화면. 검색 화면과 4개 탭은 Step 4 에서 구현한다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpdateWorker.schedule(this)
        setContent {
            EveryCorpTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.systemBarsPadding().padding(24.dp)) {
                        Text("EveryCorp", style = MaterialTheme.typography.headlineLarge)
                        Text("버전 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                        Text("만든이 ${BuildConfig.AUTHOR_NAME} (${BuildConfig.AUTHOR_EMAIL})", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
