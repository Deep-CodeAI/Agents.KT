package agents_engine.composition.branch

import agents_engine.composition.pipeline.then
import agents_engine.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

// #2134 — construction-time coverage for BranchBuilder. Same-package access
// reads BranchRoute fields directly off `branch.routes` and `builder.routes`
// to pin route-shape invariants without dispatching through the agentic loop.
//
// Note on `onNull then`: per the BranchSuspendTest comment, Agent<IN, OUT> has
// `OUT : Any` (no null output legally producible from a skill), so the null
// DISPATCH path through invokeSuspend(null) is defensive-dead-code. These tests
// pin the CONSTRUCTION side of `onNull then` — that the NullRoute is recorded
// with the right fields and that markPlaced fires on the routed agent. The
// executor lambda's runtime call to `agent.invokeSuspend(null)` is recorded
// in a closure but never legitimately invoked.
class BranchBuilderCoverageTest {

    sealed interface Animal
    data class Dog(val name: String) : Animal
    data class Cat(val name: String) : Animal

    // Two-level sealed hierarchy for the isAssignableFrom test.
    sealed interface Vehicle
    sealed interface Land : Vehicle
    data class Car(val plate: String) : Land
    data class Truck(val tonnes: Int) : Land
    data class Boat(val length: Int) : Vehicle

    private fun dogHandler(name: String = "dh") =
        agent<Dog, String>(name) { skills { skill<Dog, String>("s") { implementedBy { "dog ${it.name}" } } } }

    private fun catHandler(name: String = "ch") =
        agent<Cat, String>(name) { skills { skill<Cat, String>("s") { implementedBy { "cat ${it.name}" } } } }

    private fun anyToString(name: String) =
        agent<Any, String>(name) { skills { skill<Any, String>("s") { implementedBy { "any: ${it::class.simpleName}" } } } }

    @Test
    fun `onNull then constructs NullRoute with executor, sessionExecutor, and routedAgentName`() {
        val builder = BranchBuilder<String>()
        val handler = anyToString("null-h")
        with(builder) { onNull then handler }

        assertEquals(1, builder.routes.size)
        val route = assertIs<BranchRoute.NullRoute<String>>(builder.routes.single())
        assertEquals("null-h", route.routedAgentName)
        assertNotNull(route.sessionExecutor, "sessionExecutor must be wired by the builder")
        // The executor is a closure over `agent.invokeSuspend(null)` — calling
        // it would fail skill resolution (Any::isInstance(null) is false), but
        // its mere presence on the route is what we pin here.
        assertNotNull(route.executor)
    }

    @Test
    fun `onNull then marks the routed agent as placed — second use throws IllegalArgumentException`() {
        val handler = anyToString("shared")

        // First placement: ok.
        BranchBuilder<String>().apply { onNull then handler }

        // Second placement: markPlaced rejects.
        try {
            BranchBuilder<String>().apply { onNull then handler }
            fail("expected IllegalArgumentException — agent already placed")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                e.message!!.contains("shared") || e.message!!.contains("placed"),
                "error must reference the agent or 'placed': ${e.message}",
            )
        }
    }

    @Test
    fun `OnClause then pipeline records routedAgentName from pipeline's last agent`() {
        val src = agent<String, Animal>("src") {
            skills { skill<String, Animal>("s") { implementedBy { Dog("rex") } } }
        }
        val transform = agent<Dog, Int>("tx") {
            skills { skill<Dog, Int>("s") { implementedBy { it.name.length } } }
        }
        val finalize = agent<Int, String>("fin") {
            skills { skill<Int, String>("s") { implementedBy { "len=$it" } } }
        }
        val branch = src.branch<String, Animal, String> {
            on<Dog>() then (transform then finalize)  // pipeline route
            on<Cat>() then catHandler()
        }

        val dogRoute = branch.routes.filterIsInstance<BranchRoute.TypeRoute<String>>()
            .single { it.klass == Dog::class }
        assertEquals("fin", dogRoute.routedAgentName, "must be last pipeline agent's name")
        assertNotNull(dogRoute.sessionExecutor, "pipeline route must wire sessionExecutor")

        // End-to-end the pipeline path still executes.
        assertEquals("len=3", branch.invoke("anything"))
    }

    @Test
    fun `branchNullable produces the same route shape as branch on a non-null sealed source`() {
        val src1 = agent<String, Animal>("a1") {
            skills { skill<String, Animal>("s") { implementedBy { Dog("rex") } } }
        }
        val src2 = agent<String, Animal>("a2") {
            skills { skill<String, Animal>("s") { implementedBy { Dog("rex") } } }
        }
        val viaBranch = src1.branch<String, Animal, String> {
            on<Dog>() then dogHandler("d1")
            on<Cat>() then catHandler("c1")
        }
        val viaNullable = src2.branchNullable<String, Animal, String> {
            on<Dog>() then dogHandler("d2")
            on<Cat>() then catHandler("c2")
        }

        // Same shape — same count + same route classes in the same positions.
        assertEquals(viaBranch.routes.size, viaNullable.routes.size)
        viaBranch.routes.zip(viaNullable.routes).forEach { (a, b) ->
            assertEquals(a::class, b::class, "route classes must match at the same index")
        }
        // Sanity — viaNullable returns the typed Branch and dispatches.
        assertEquals("dog rex", viaNullable.invoke("x"))
    }

    @Test
    fun `validateSealedCompleteness error names uncovered subclasses and points to onElse`() {
        val src = agent<String, Animal>("src") {
            skills { skill<String, Animal>("s") { implementedBy { Dog("rex") } } }
        }
        try {
            src.branch<String, Animal, String> {
                on<Dog>() then dogHandler()
                // missing Cat, no onElse
            }
            fail("expected IllegalArgumentException for incomplete sealed coverage")
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            assertTrue("Cat" in msg, "must name uncovered subclass Cat: $msg")
            assertTrue("Animal" in msg, "must name the sealed source type: $msg")
            assertTrue("onElse" in msg, "must mention the onElse escape hatch: $msg")
        }
    }

    @Test
    fun `validateSealedCompleteness short-circuits when an ElseRoute is present`() {
        val src = agent<String, Animal>("src") {
            skills { skill<String, Animal>("s") { implementedBy { Dog("rex") } } }
        }
        // Cat unrouted but onElse covers it → construction must succeed.
        val branch = src.branch<String, Animal, String> {
            on<Dog>() then dogHandler()
            onElse then anyToString("else")
        }
        assertEquals(2, branch.routes.size)
        assertEquals("dog rex", branch.invoke("any"))
    }

    @Test
    fun `validateSealedCompleteness covers sub-subclasses via on parent through isAssignableFrom`() {
        // Vehicle is sealed with Land (also sealed: Car, Truck) and Boat.
        // A single on<Land>() route should cover Car and Truck via isAssignableFrom;
        // Boat needs its own route (or onElse).
        val src = agent<String, Vehicle>("src") {
            skills { skill<String, Vehicle>("s") { implementedBy { Car("ZZ-001") } } }
        }
        val landHandler = agent<Land, String>("land") {
            skills { skill<Land, String>("s") { implementedBy { "land" } } }
        }
        val boatHandler = agent<Boat, String>("boat") {
            skills { skill<Boat, String>("s") { implementedBy { "boat ${it.length}m" } } }
        }

        // No onElse, no per-subclass routes for Car/Truck — only on<Land>() and on<Boat>().
        // validateSealedCompleteness must accept this via Land being a covered ancestor.
        val branch = src.branch<String, Vehicle, String> {
            on<Land>() then landHandler
            on<Boat>() then boatHandler
        }

        assertEquals(2, branch.routes.size)
        assertEquals("land", branch.invoke("any"))
    }

    @Test
    fun `on returns an OnClause carrying the requested KClass and a cast that yields T`() {
        val builder = BranchBuilder<String>()
        val clause = with(builder) { on<Dog>() }
        assertSame(Dog::class, clause.klass)
        // castFn unwraps to the right runtime type.
        val rex = Dog("rex")
        assertSame(rex, clause.castFn(rex))
    }
}
