package com.processdesk.corpus;

import com.processdesk.harness.*;
import java.nio.file.*;
import java.util.List;

/** Prints what the model would see for one DMN file, and whether the gates accept it. */
public final class Inspect {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            String xml = Files.readString(Path.of(path));
            System.out.println("══ " + path + "  (" + xml.length() / 1024 + " KB)");
            List<GateResult> gates = new DecisionGates().run(xml);
            gates.forEach(g -> System.out.println("   gate " + g.id() + ": " + (g.ok() ? "PASS" : "FAIL — " + g.detail())));
            DecisionProjection p = DecisionProjection.of(xml);
            String rendered = p.render();
            long blank = p.decisions().stream().filter(d -> d.rules().isEmpty()).count();
            System.out.println("   decisions=" + p.decisions().size() + " blank=" + blank
                    + " inputs=" + p.inputs().size()
                    + " | vocab: columns=" + p.vocabulary().columns().size()
                    + " conditions=" + p.vocabulary().conditions().size()
                    + " outcomes=" + p.vocabulary().outcomes().size());
            System.out.println("   projection " + rendered.length() + " chars (~" + rendered.length() / 4 + " tokens), file ~"
                    + xml.length() / 4 + " tokens → " + (xml.length() / Math.max(1, rendered.length())) + "x smaller");
            System.out.println("   ─── first 900 chars ───");
            System.out.println(rendered.substring(0, Math.min(900, rendered.length())));
            System.out.println();
        }
    }
}
