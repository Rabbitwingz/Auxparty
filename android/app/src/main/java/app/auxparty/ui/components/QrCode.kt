package app.auxparty.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code for [text], drawn module by module so it stays crisp at any size. Put it on
 * a light surface with some padding: scanners need contrast and a quiet zone.
 */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    description: String = "QR code",
) {
    val matrix = remember(text) {
        runCatching {
            // Width and height 0: one pixel per module, sized by the canvas below.
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / m.width
        // A hair of overlap hides seams between neighbouring modules.
        val module = Size(cell + 0.5f, cell + 0.5f)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y)) drawRect(color, topLeft = Offset(x * cell, y * cell), size = module)
            }
        }
    }
}
