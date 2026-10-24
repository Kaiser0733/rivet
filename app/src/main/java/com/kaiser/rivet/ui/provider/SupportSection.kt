package com.kaiser.rivet.ui.provider

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.ui.RivetOutlinedButton

private const val SUPPORT_URL = "https://www.buymeacoffee.com/Vastraalabs"
private const val UPI_ID = "maybeamardeep@fam"

@Composable
internal fun SupportSection() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.support_body), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        RivetOutlinedButton(onClick = { openSupport(context) },
            modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.support_coffee))
        }
        // The UPI id is copied rather than opened: there is no guaranteed
        // handler for a payment deep link, and a missing payment app must not
        // look like a dead tap.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { copyUpi(context) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.support_upi_label),
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(UPI_ID, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun openSupport(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, R.string.support_no_browser, Toast.LENGTH_SHORT).show()
    }
}

private fun copyUpi(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Rivet UPI", UPI_ID))
    Toast.makeText(context, R.string.support_upi_copied, Toast.LENGTH_SHORT).show()
}
