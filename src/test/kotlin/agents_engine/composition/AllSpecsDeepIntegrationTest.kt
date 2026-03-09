package agents_engine.composition

import agents_engine.core.*
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals

class AllSpecsDeepIntegrationTest {

    @Test
    fun complexSystemsAreNotComplexNow() {
        open class Spec()
        class UseCase : Spec()
        class GlossaryTerm : Spec()
        class OuterReference : Spec()
        class SystemActor : Spec()
        class Feature : Spec()
        class Requirement : Spec()

        data class SpecsParcel(
            val description: String,
            val useCases: List<UseCase> = emptyList(),
            val glossaryTerms: List<GlossaryTerm> = emptyList(),
            val outerReferences: List<OuterReference> = emptyList(),
            val systemActors: List<SystemActor> = emptyList(),
            val features: List<Feature> = emptyList(),
            val requirements: List<Requirement> = emptyList(),
        )

        var specsGoodness = 0f
        val input = "Do something ppl want"

        var currentState = SpecsParcel(description = input)
        var iteration = 0

        val useCasesMaster     = agent<SpecsParcel, Spec>("use_case_master")      { execute { UseCase() } }
        val glossaryMaster     = agent<SpecsParcel, Spec>("glossary_master")      { execute { GlossaryTerm() } }
        val outerRefsMaster    = agent<SpecsParcel, Spec>("outer_refs_master")    { execute { OuterReference() } }
        val systemActorsMaster = agent<SpecsParcel, Spec>("system_actors_master") { execute { SystemActor() } }
        val featuresMaster     = agent<SpecsParcel, Spec>("features_master")      { execute { Feature() } }
        val requirementsMaster = agent<SpecsParcel, Spec>("requirements_master")  { execute { Requirement() } }

        val specsGenerationPipeline = (useCasesMaster / glossaryMaster / outerRefsMaster /
                systemActorsMaster / featuresMaster / requirementsMaster)

        val specsGatheringMaster = agent<List<Spec>, SpecsParcel>("specsGathering_master") {
            execute { specs ->
                SpecsParcel(
                    description    = currentState.description,
                    useCases       = specs.filterIsInstance<UseCase>(),
                    glossaryTerms  = specs.filterIsInstance<GlossaryTerm>(),
                    outerReferences = specs.filterIsInstance<OuterReference>(),
                    systemActors   = specs.filterIsInstance<SystemActor>(),
                    features       = specs.filterIsInstance<Feature>(),
                    requirements   = specs.filterIsInstance<Requirement>(),
                )
            }
        }

        val total = specsGenerationPipeline then specsGatheringMaster

        val specsEvaluator = agent<SpecsParcel, Float>("specsEvaluator") {
            execute { specs ->
                // Score improves with each iteration; reaches 90+ after 3 rounds
                val completeness = (specs.useCases.size + specs.glossaryTerms.size +
                        specs.outerReferences.size + specs.systemActors.size +
                        specs.features.size + specs.requirements.size).toFloat()
                minOf(completeness * 15f * (iteration + 1), 100f)
            }
        }

        while (specsGoodness < 90.0f) {
            val newSpecs = total(currentState)
            specsGoodness = specsEvaluator(newSpecs)
            currentState = newSpecs
            iteration++
        }

        assert(specsGoodness >= 90.0f)
        assert(currentState.useCases.isNotEmpty())
        assert(currentState.glossaryTerms.isNotEmpty())
        assert(currentState.systemActors.isNotEmpty())

    }

    @Test
    fun specTypesAndCountsArePreservedThroughPipeline() {
        open class Spec()
        class UseCase : Spec()
        class GlossaryTerm : Spec()
        class OuterReference : Spec()
        class SystemActor : Spec()
        class Feature : Spec()
        class Requirement : Spec()

        data class SpecsParcel(
            val description: String,
            val useCases: List<UseCase> = emptyList(),
            val glossaryTerms: List<GlossaryTerm> = emptyList(),
            val outerReferences: List<OuterReference> = emptyList(),
            val systemActors: List<SystemActor> = emptyList(),
            val features: List<Feature> = emptyList(),
            val requirements: List<Requirement> = emptyList(),
        )

        val rng = Random(System.currentTimeMillis())
        val useCaseCount     = rng.nextInt(1, 20)
        val glossaryCount    = rng.nextInt(1, 20)
        val outerRefCount    = rng.nextInt(1, 20)
        val actorCount       = rng.nextInt(1, 20)
        val featureCount     = rng.nextInt(1, 20)
        val requirementCount = rng.nextInt(1, 20)

        // Each agent produces a random-sized list of its own spec type
        val useCasesMaster     = agent<SpecsParcel, List<Spec>>("uc_master")   { execute { List(useCaseCount)     { UseCase() } } }
        val glossaryMaster     = agent<SpecsParcel, List<Spec>>("gl_master")   { execute { List(glossaryCount)    { GlossaryTerm() } } }
        val outerRefsMaster    = agent<SpecsParcel, List<Spec>>("or_master")   { execute { List(outerRefCount)    { OuterReference() } } }
        val systemActorsMaster = agent<SpecsParcel, List<Spec>>("sa_master")   { execute { List(actorCount)       { SystemActor() } } }
        val featuresMaster     = agent<SpecsParcel, List<Spec>>("ft_master")   { execute { List(featureCount)     { Feature() } } }
        val requirementsMaster = agent<SpecsParcel, List<Spec>>("req_master")  { execute { List(requirementCount) { Requirement() } } }

        val parallel = (useCasesMaster / glossaryMaster / outerRefsMaster /
                systemActorsMaster / featuresMaster / requirementsMaster)

        // Gathering master receives List<List<Spec>> — one list per parallel agent
        val gatheringMaster = agent<List<List<Spec>>, SpecsParcel>("gathering_master") {
            execute { specLists ->
                val all = specLists.flatten()
                SpecsParcel(
                    description     = "assembled",
                    useCases        = all.filterIsInstance<UseCase>(),
                    glossaryTerms   = all.filterIsInstance<GlossaryTerm>(),
                    outerReferences = all.filterIsInstance<OuterReference>(),
                    systemActors    = all.filterIsInstance<SystemActor>(),
                    features        = all.filterIsInstance<Feature>(),
                    requirements    = all.filterIsInstance<Requirement>(),
                )
            }
        }

        val pipeline = parallel then gatheringMaster
        val result = pipeline(SpecsParcel("input"))

        assertEquals(useCaseCount,     result.useCases.size)
        assertEquals(glossaryCount,    result.glossaryTerms.size)
        assertEquals(outerRefCount,    result.outerReferences.size)
        assertEquals(actorCount,       result.systemActors.size)
        assertEquals(featureCount,     result.features.size)
        assertEquals(requirementCount, result.requirements.size)
    }
}