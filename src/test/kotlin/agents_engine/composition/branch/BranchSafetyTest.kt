package agents_engine.composition.branch

import agents_engine.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Test fixtures for #640 — sealed hierarchy with three subtypes. */
sealed interface Animal
data class Dog(val name: String) : Animal
data class Cat(val name: String) : Animal
data class Fish(val name: String) : Animal

/**
 * Tests for #640 — Branch handles null cleanly via `onNull`, matches sealed
 * subtypes via `KClass.isInstance` (not exact-class), and validates
 * sealed-hierarchy completeness at construction.
 */
class BranchSafetyTest {

    @Test
    fun `on(Animal) matches Dog (subtype routing)`() {
        val srcDog = agent<String, Animal>("d") {
            skills { skill<String, Animal>("s", "") { implementedBy { Dog("rex") } } }
        }
        val animalDown = agent<Animal, String>("anyDog") {
            skills { skill<Animal, String>("s", "") { implementedBy { "an animal: ${it::class.simpleName}" } } }
        }
        val elseDown = agent<Any, String>("elseDog") {
            skills { skill<Any, String>("s", "") { implementedBy { "else" } } }
        }

        val branchedDog = srcDog.branch<String, Animal, String> {
            on<Animal>() then animalDown
            onElse then elseDown
        }
        assertEquals("an animal: Dog", branchedDog("x"))
    }

    @Test
    fun `on(Animal) matches Cat (subtype routing)`() {
        val srcCat = agent<String, Animal>("c") {
            skills { skill<String, Animal>("s", "") { implementedBy { Cat("mia") } } }
        }
        val animalDown = agent<Animal, String>("anyCat") {
            skills { skill<Animal, String>("s", "") { implementedBy { "an animal: ${it::class.simpleName}" } } }
        }
        val elseDown = agent<Any, String>("elseCat") {
            skills { skill<Any, String>("s", "") { implementedBy { "else" } } }
        }

        val branchedCat = srcCat.branch<String, Animal, String> {
            on<Animal>() then animalDown
            onElse then elseDown
        }
        assertEquals("an animal: Cat", branchedCat("x"))
    }

    @Test
    fun `incomplete sealed hierarchy without onElse throws at construction`() {
        val src = agent<String, Animal>("s") {
            skills { skill<String, Animal>("s", "") { implementedBy { Dog("rex") } } }
        }
        val dogDown = agent<Dog, String>("d-incomplete") {
            skills { skill<Dog, String>("s", "") { implementedBy { it.name } } }
        }
        val catDown = agent<Cat, String>("c-incomplete") {
            skills { skill<Cat, String>("s", "") { implementedBy { it.name } } }
        }

        try {
            src.branch<String, Animal, String> {
                on<Dog>() then dogDown
                on<Cat>() then catDown
                // missing Fish AND no onElse → must throw
            }
            fail("expected completeness check to throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Fish"), "error must name uncovered subtype: ${e.message}")
        }
    }

    @Test
    fun `incomplete sealed hierarchy with onElse constructs and uncovered routes to else`() {
        val src = agent<String, Animal>("s") {
            skills { skill<String, Animal>("s", "") { implementedBy { Fish("nemo") } } }
        }
        val dogDown = agent<Dog, String>("d") {
            skills { skill<Dog, String>("s", "") { implementedBy { it.name } } }
        }
        val catDown = agent<Cat, String>("c") {
            skills { skill<Cat, String>("s", "") { implementedBy { it.name } } }
        }
        val elseDown = agent<Any, String>("else") {
            skills { skill<Any, String>("s", "") { implementedBy { "fallback" } } }
        }

        val b = src.branch<String, Animal, String> {
            on<Dog>() then dogDown
            on<Cat>() then catDown
            onElse then elseDown
        }
        assertEquals("fallback", b("x"))
    }

    @Test
    fun `complete sealed coverage requires no onElse`() {
        val src = agent<String, Animal>("s") {
            skills { skill<String, Animal>("s", "") { implementedBy { Fish("nemo") } } }
        }
        val dogDown = agent<Dog, String>("d") {
            skills { skill<Dog, String>("s", "") { implementedBy { it.name } } }
        }
        val catDown = agent<Cat, String>("c") {
            skills { skill<Cat, String>("s", "") { implementedBy { it.name } } }
        }
        val fishDown = agent<Fish, String>("f") {
            skills { skill<Fish, String>("s", "") { implementedBy { it.name } } }
        }

        val b = src.branch<String, Animal, String> {
            on<Dog>() then dogDown
            on<Cat>() then catDown
            on<Fish>() then fishDown
        }
        assertEquals("nemo", b("x"))
    }
}
