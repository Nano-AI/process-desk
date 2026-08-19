package com.processdesk.ai;

import com.processdesk.harness.BpmnDocument;
import com.processdesk.harness.ProcessProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.processdesk.Fixtures;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StepResolverTest {

    private final StepResolver resolver = new StepResolver();
    private BpmnDocument doc;
    private String xml;

    @BeforeEach
    void loadFixture() throws Exception {
        xml = Fixtures.refundProcess();
        doc = BpmnDocument.parse(xml);
    }

    @Test
    void resolvesAnExactName() {
        StepResolver.Resolution result = resolver.resolve(doc, "Submit Request");

        assertTrue(result.resolved());
        assertTrue(result.exact());
        assertEquals("Task_Submit", result.id());
    }

    @Test
    void ignoresCaseAndSpacing() {
        StepResolver.Resolution result = resolver.resolve(doc, "  submit   request ");

        assertTrue(result.resolved());
        assertTrue(result.exact(), "differing only by case or spacing is still the same step");
        assertEquals("Task_Submit", result.id());
    }

    @Test
    void toleratesATypo() {
        StepResolver.Resolution result = resolver.resolve(doc, "Submit Requst");

        assertTrue(result.resolved());
        assertFalse(result.exact(), "a corrected name is a guess, and must be reported as one");
        assertEquals("Task_Submit", result.id());
    }

    @Test
    void offersTheRealNamesWhenNothingMatches() {
        StepResolver.Resolution result = resolver.resolve(doc, "Shipping Label");

        assertFalse(result.resolved());
        assertFalse(result.ambiguous(), "nothing came close; this is not a choice between steps");
        assertEquals(List.of("Submit Request", "Manager Approval"), result.options());
    }

    @Test
    void refusesToGuessBetweenTwoEquallyCloseSteps() throws Exception {
        // Two steps one edit apart from the request, and from each other.
        String ambiguous = xml
                .replace("name=\"Submit Request\"", "name=\"Review A\"")
                .replace("name=\"Manager Approval\"", "name=\"Review B\"");

        StepResolver.Resolution result = resolver.resolve(BpmnDocument.parse(ambiguous), "Review C");

        assertFalse(result.resolved(), "picking one would be a silent guess about intent");
        assertTrue(result.ambiguous(), "the user must be asked which one, not told what exists");
        assertEquals(List.of("Review A", "Review B"), result.options());
    }

    @Test
    void shortNamesGetNoTypoTolerance() throws Exception {
        // "Pick" and "Pack" are one edit apart but mean different things; correcting one
        // into the other would silently edit a step the user did not ask for.
        String shortNames = xml.replace("name=\"Submit Request\"", "name=\"Pack\"");

        StepResolver.Resolution result = resolver.resolve(BpmnDocument.parse(shortNames), "Pick");

        assertFalse(result.resolved());
        assertTrue(result.options().contains("Pack"), "the user still gets told what does exist");
    }

    @Test
    void longNamesStillToleratePlausibleTypos() {
        assertTrue(resolver.resolve(doc, "Manger Aproval").resolved(),
                "two edits on a fourteen-character name is a typo, not a different step");
    }

    @Test
    void projectionCarriesTheFlowOrderAndNothingElse() {
        ProcessProjection projection = ProcessProjection.of(xml);

        assertEquals("Refund Request", projection.processName());
        assertEquals(List.of("Submit Request", "Manager Approval"), projection.steps());

        String rendered = projection.render();
        assertEquals("Process: Refund Request\nFlow: Submit Request → Manager Approval", rendered);
        assertFalse(rendered.contains("Task_Submit"), "identifiers must not reach the model");
        assertFalse(rendered.contains("Bounds"), "geometry must not reach the model");
    }

    @Test
    void projectionIsFarSmallerThanTheFile() {
        String rendered = ProcessProjection.of(xml).render();

        assertTrue(rendered.length() * 20 < xml.length(),
                () -> "expected a large reduction, got " + rendered.length() + " vs " + xml.length());
    }

    @Test
    void schemaLimitsTheModelToStepsThatExist() {
        Map<String, Object> schema = IntentSchema.forProcess(ProcessProjection.of(xml));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) properties.get("targetStepName");

        assertEquals(List.of("Submit Request", "Manager Approval"), target.get("enum"));
        assertEquals(List.of("kind", "targetStepName", "value", "declineReason"), schema.get("required"),
                "a small model stops at the first optional field, leaving a half-formed edit");
    }

    @Test
    void schemaOmitsTheEnumWhenThereAreNoSteps() {
        Map<String, Object> schema = IntentSchema.forProcess(ProcessProjection.EMPTY);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) properties.get("targetStepName");

        assertFalse(target.containsKey("enum"), "an empty enum would forbid every possible answer");
    }
}
