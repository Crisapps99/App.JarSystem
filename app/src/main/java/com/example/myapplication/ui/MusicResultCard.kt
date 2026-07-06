import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// Colores base
private val ColorBgDark = Color(0xFF1C1C1E)
private val ColorCardBorder = Color(0xFF3A3A50).copy(alpha = 0.5f)
private val ColorDivider = Color(0xFF3A3A50).copy(alpha = 0.6f)
private val ColorSpotify = Color(0xFF1DB954)
private val ColorYouTube = Color(0xFFFF0000)

@Composable
fun MusicResultCard(
    title: String,
    artist: String,
    album: String,        // NUEVO: Para el nombre del álbum
    genre: String,        // NUEVO: Para el género
    durationMs: Long,     // NUEVO: Tiempo en milisegundos que viene en tu JSON
    coverUrl: String,
    externalUrls: List<String>,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    // Identificación inteligente de links
    val spotifyUrl = externalUrls.find { it.contains("spotify.com", ignoreCase = true) || it.contains("spotify.com", ignoreCase = true) }
    val youtubeUrl = externalUrls.find { it.contains("youtube.com", ignoreCase = true) || it.contains("youtu.be", ignoreCase = true) }

    // Conversión de milisegundos a formato MM:SS (Ej: 318000 -> 05:18)
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val durationText = String.format("%02d:%02d", minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(26.dp), spotColor = Color.Black.copy(alpha = 0.4f))
            .background(ColorBgDark, RoundedCornerShape(26.dp))
            .border(1.dp, ColorCardBorder, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
    ) {
        // Fondo con degradado "Glass"
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF2A2A3A).copy(alpha = 0.8f),
                            ColorBgDark
                        )
                    )
                )
        )

        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // ─────────────────────────────────────────────────────────────────
            // 1. Encabezado Minimalista
            // ─────────────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFF4DEEE9), CircleShape)
                            .shadow(8.dp, spotColor = Color(0xFF4DEEE9))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MÚSICA IDENTIFICADA",
                        color = Color(0xFFA0A0B0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(0xFF3A3A50).copy(alpha = 0.4f), CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ─────────────────────────────────────────────────────────────────
            // 2. Información Principal (Portada, Título, Artista)
            // ─────────────────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = "Portada de $title",
                        modifier = Modifier
                            .size(96.dp)
                            .shadow(12.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.6f))
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF2A2A3A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("", fontSize = 36.sp)
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 24.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = artist,
                        color = Color(0xFF4DEEE9), // Color de acento para el artista
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ─────────────────────────────────────────────────────────────────
            // 3. Metadatos Organizados (Álbum, Género, Duración)
            // ─────────────────────────────────────────────────────────────────

            // Línea separadora superior
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ColorDivider))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min) // Obliga a la fila a tomar la altura de su contenido
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bloque: Álbum
                MetadataItem(
                    label = "ÁLBUM",
                    value = if (album.isNotBlank()) album else "Desconocido",
                    modifier = Modifier.weight(1.2f)
                )

                // Divisor Vertical
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(ColorDivider))

                // Bloque: Género
                MetadataItem(
                    label = "GÉNERO",
                    value = if (genre.isNotBlank()) genre else "--",
                    modifier = Modifier.weight(0.9f)
                )

                // Divisor Vertical
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(ColorDivider))

                // Bloque: Duración
                MetadataItem(
                    label = "TIEMPO",
                    value = durationText,
                    modifier = Modifier.weight(0.7f)
                )
            }

            // Línea separadora inferior
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ColorDivider))

            Spacer(modifier = Modifier.height(20.dp))

            // ─────────────────────────────────────────────────────────────────
            // 4. Botones de Acción
            // ─────────────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (spotifyUrl != null) {
                    PlatformButton(
                        text = "Spotify",
                        accentColor = ColorSpotify,
                        modifier = Modifier.weight(1f),
                        onClick = { openExternalUrl(context, spotifyUrl) }
                    )
                }

                if (youtubeUrl != null) {
                    PlatformButton(
                        text = "YouTube",
                        accentColor = ColorYouTube,
                        modifier = Modifier.weight(1f),
                        onClick = { openExternalUrl(context, youtubeUrl) }
                    )
                }

                if (spotifyUrl == null && youtubeUrl == null && externalUrls.isNotEmpty()) {
                    PlatformButton(
                        text = "Escuchar",
                        accentColor = Color(0xFF4DEEE9),
                        modifier = Modifier.weight(1f),
                        onClick = { openExternalUrl(context, externalUrls.first()) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Componentes Secundarios
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MetadataItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0xFF888899),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlatformButton(
    text: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(Color(0xFF2C2C3A), RoundedCornerShape(14.dp))
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accentColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun openExternalUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}