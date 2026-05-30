package agents_engine.model

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Programmatic PNG fixtures for vision-input tests (#2470). Images are
 * rendered from `BufferedImage` so the test set ships in source — no
 * binary assets, no external download — and every byte is reproducible
 * across machines / CI runs.
 *
 * Size discipline: 256×256 keeps base64 payloads ~5 KB, well within
 * cheap-tier provider limits. Hand-tested with qwen3-vl:8b / Haiku 4.5
 * / gpt-4o-mini — all three identify both fixtures reliably at this
 * size.
 */
object VisionFixtures {

    /**
     * 256×256 PNG with three colored squares on a white background:
     * red, blue, green — spaced far enough apart that even small
     * vision models count them reliably. Used by the
     * "agent counts squares" eval.
     */
    fun threeSquaresPng(): ByteArray {
        val w = 256
        val h = 256
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        // Three obviously-distinct squares at well-separated positions.
        val side = 50
        g.color = Color.RED
        g.fillRect(20, 20, side, side)
        g.color = Color.BLUE
        g.fillRect(110, 100, side, side)
        g.color = Color.GREEN
        g.fillRect(190, 190, side, side)
        // Black outlines so each square is unambiguously countable.
        g.color = Color.BLACK
        g.stroke = BasicStroke(2f)
        g.drawRect(20, 20, side, side)
        g.drawRect(110, 100, side, side)
        g.drawRect(190, 190, side, side)
        g.dispose()
        return img.toPngBytes()
    }

    /**
     * 256×256 PNG of a simple house: triangular roof, square body, door,
     * two windows. Drawn with thick black outlines so even tiny vision
     * models classify it. Used by the "agent identifies the house" eval.
     */
    fun housePng(): ByteArray {
        val w = 256
        val h = 256
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        // Body: 130×130 square, centred-ish.
        val bodyX = 60
        val bodyY = 110
        val bodyW = 130
        val bodyH = 110
        g.color = Color(255, 230, 200) // warm beige walls
        g.fillRect(bodyX, bodyY, bodyW, bodyH)
        // Roof: triangle spanning slightly wider than the body, peak above.
        val roofXs = intArrayOf(bodyX - 15, bodyX + bodyW / 2, bodyX + bodyW + 15)
        val roofYs = intArrayOf(bodyY, bodyY - 80, bodyY)
        g.color = Color(180, 70, 60) // terracotta roof
        g.fillPolygon(roofXs, roofYs, 3)
        // Door: small rectangle bottom-centre of the body.
        g.color = Color(120, 75, 40) // brown door
        g.fillRect(bodyX + bodyW / 2 - 18, bodyY + bodyH - 60, 36, 60)
        // Two windows: small squares left + right of the door.
        g.color = Color(140, 180, 220) // blue windows
        g.fillRect(bodyX + 15, bodyY + 20, 30, 30)
        g.fillRect(bodyX + bodyW - 45, bodyY + 20, 30, 30)
        // Outlines: 3-px black so the silhouette is unambiguous.
        g.color = Color.BLACK
        g.stroke = BasicStroke(3f)
        g.drawRect(bodyX, bodyY, bodyW, bodyH)
        g.drawPolygon(roofXs, roofYs, 3)
        g.drawRect(bodyX + bodyW / 2 - 18, bodyY + bodyH - 60, 36, 60)
        g.drawRect(bodyX + 15, bodyY + 20, 30, 30)
        g.drawRect(bodyX + bodyW - 45, bodyY + 20, 30, 30)
        g.dispose()
        return img.toPngBytes()
    }

    /** Encode the bytes to base64 — what every adapter ultimately sends on the wire. */
    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun BufferedImage.toPngBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(this, "png", out)
        return out.toByteArray()
    }
}
