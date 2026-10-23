package com.autoclicker.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.autoclicker.app.data.FlowStore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlowStore.init(applicationContext)
        if (FlowStore.recoveredFromCorruption) {
            Toast.makeText(
                this,
                "Dữ liệu cũ bị lỗi, đã sao lưu vào flows.json.bak trong bộ nhớ app",
                Toast.LENGTH_LONG
            ).show()
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Sau khi Start / đặt vị trí, đẩy app xuống nền để lộ game phía sau.
                    App(onSendToBack = { moveTaskToBack(true) })
                }
            }
        }
    }

    override fun onStop() {
        FlowStore.flush()
        super.onStop()
    }
}
