package agents_engine.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Locale
import kotlin.test.assertEquals

class BranchExecutionTest {

    sealed interface Shape
    data class Circle(val radius: Double) : Shape
    data class Rectangle(val w: Double, val h: Double) : Shape
    data class Triangle(val base: Double, val height: Double) : Shape

    private val classify = agent<String, Shape>("classify") {
        execute { input ->
            when {
                input.startsWith("c") -> Circle(input.length.toDouble())
                input.startsWith("r") -> Rectangle(2.0, 3.0)
                else                  -> Triangle(4.0, 5.0)
            }
        }
    }

    @Test
    fun `branch routes each variant to correct handler`() {
        val branch = classify.branch {
            on<Circle>()    then agent<Circle, String>("c")    { execute { "circle r=${it.radius}" } }
            on<Rectangle>() then agent<Rectangle, String>("r") { execute { "rect ${it.w}x${it.h}" } }
            on<Triangle>()  then agent<Triangle, String>("t")  { execute { "tri b=${it.base}" } }
        }

        assertEquals("circle r=6.0", branch("circle"))
        assertEquals("rect 2.0x3.0", branch("rect"))
        assertEquals("tri b=4.0",    branch("triangle"))
    }

    @Test
    fun `branch with pipeline on a variant`() {
        val area    = agent<Circle, Double>("area")   { execute { Math.PI * it.radius * it.radius } }
        val rounded = agent<Double, String>("round")  { execute { "%.2f".format(Locale.US, it) } }

        val branch = classify.branch {
            on<Circle>()    then (area then rounded)
            on<Rectangle>() then agent<Rectangle, String>("r") { execute { "rect" } }
            on<Triangle>()  then agent<Triangle, String>("t")  { execute { "tri" } }
        }

        assertEquals("%.2f".format(Locale.US, Math.PI * 36.0), branch("circle"))
    }

    @Test
    fun `branch is composable after pipeline`() {
        val prepare = agent<Int, String>("prepare") { execute { if (it > 0) "circle" else "rect" } }

        val branch = classify.branch {
            on<Circle>()    then agent<Circle, Int>("c")    { execute { it.radius.toInt() } }
            on<Rectangle>() then agent<Rectangle, Int>("r") { execute { (it.w * it.h).toInt() } }
            on<Triangle>()  then agent<Triangle, Int>("t")  { execute { (it.base * it.height / 2).toInt() } }
        }

        val pipeline = prepare then branch
        assertEquals(6, pipeline(1))   // "circle" → Circle(6.0) → 6
        assertEquals(6, pipeline(-1))  // "rect" → Rectangle(2.0,3.0) → 6
    }

    @Test
    fun `branch composable before agent`() {
        val branch = classify.branch {
            on<Circle>()    then agent<Circle, Double>("c")    { execute { it.radius } }
            on<Rectangle>() then agent<Rectangle, Double>("r") { execute { it.w * it.h } }
            on<Triangle>()  then agent<Triangle, Double>("t")  { execute { it.base * it.height / 2 } }
        }

        val wrap = agent<Double, String>("wrap") { execute { "area=%.1f".format(Locale.US, it) } }
        val pipeline = branch then wrap

        assertEquals("area=6.0",  pipeline("circle"))
        assertEquals("area=6.0",  pipeline("rect"))
        assertEquals("area=10.0", pipeline("triangle"))
    }

    @Test
    fun `unhandled variant throws at invocation`() {
        val branch = classify.branch {
            on<Circle>()    then agent<Circle, String>("c") { execute { "circle" } }
            on<Rectangle>() then agent<Rectangle, String>("r") { execute { "rect" } }
        }

        assertThrows<IllegalStateException> {
            branch("triangle")
        }
    }

    @Test
    fun `agents inside branch are tracked`() {
        val circleHandler = agent<Circle, String>("c") { execute { "circle" } }

        classify.branch {
            on<Circle>()    then circleHandler
            on<Rectangle>() then agent<Rectangle, String>("r") { execute { "rect" } }
            on<Triangle>()  then agent<Triangle, String>("t")  { execute { "tri" } }
        }

        assertThrows<IllegalArgumentException> {
            circleHandler then agent<String, String>("x") { execute { it } }
        }
    }
}
