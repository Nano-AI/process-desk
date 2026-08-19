# What the Editor Can Do, and What the Assistant Can

The embedded editors are `@kie-tools/dmn-editor-standalone` and `@kie-tools/bpmn-editor-standalone`
10.2.0. A person clicking around in them can build essentially any DMN 1.5 or BPMN 2.0 model. The
assistant can perform **nine operations**, and this document is the difference between those two
numbers.

Counts below are from the 1,241 DMN and 419 BPMN files in `corpora/`, which is the closest thing
we have to a sample of what real models contain.

## What the assistant can do today

| | Operation | Since |
|---|---|---|
| DMN | `set_cell` — change one cell of one rule | the beginning |
| DMN | `add_rule` — append a rule to a table | today |
| DMN | `rename_decision` — and every reference to it | today |
| DMN | `list_decisions`, `show_decision`, `check`, `calculate` | read/verify only |
| BPMN | rename a step | the beginning |
| BPMN | add a task after an existing one | the beginning |

Nine, and only three of them write anything.

## The direct answers

**Create a decision node with a decision table?** No. Nothing creates a `decision` element.

**Connect two decisions?** No. `informationRequirement` appears 2,816 times in the corpus and the
assistant cannot create, delete or re-point one.

**Write a `for` expression, or any boxed expression?** No. It cannot write a `literalExpression`,
`context`, `invocation`, `for`, `every`, `some`, `filter`, `conditional`, `list`, `relation` or
`functionDefinition` — and cannot *read* them either, which is the 93% figure below.

**Edit BPMN properties?** Only `name`. Not documentation, assignments, script bodies, timer
definitions, conditions, multi-instance, or anything under `extensionElements` (580 occurrences).

**Create or modify data types?** No. `itemDefinition` appears 1,810 times and is entirely
untouched.

**Structs inside structs?** No — and they are real: **42** item definitions in the corpus nest two
levels deep, 521 nest one.

## The gap, by weight

### Decision logic — the assistant reads 7% of it

```
literalExpression      9138     ← cannot read or write
decisionTable           648     ← the only thing it understands
context                 477     ← cannot read or write
invocation              313
relation / functionDefinition / list / filter / conditional / for / every / some   ~200
```

**Decision tables are 6% of decision logic in the corpus.** Everything the assistant does rests on
that 6%. This is the single largest gap and the one behind every "why can't it see that decision"
question — `docs/07` records five of eleven decisions in the lending model rendering blank, and
this is why.

### Model structure — nothing can be created

```
decision               5356     businessKnowledgeModel   521
inputData              2014     decisionService          377
import                  137     knowledgeSource          102
textAnnotation           81     association               37
```

No element of any of these kinds can be created, deleted, or re-typed.

### Data types — untouched

```
itemDefinition         1810     nesting:  flat 1247 · struct 521 · struct-in-struct 42
itemComponent          2409
allowedValues           595     typeConstraint  17
```

### Wiring — untouched

```
informationRequirement 2816     knowledgeRequirement     634
requiredInput          2017     requiredKnowledge        634
requiredDecision        837     authorityRequirement     162
```

### BPMN — two operations against a large surface

```
activities   scriptTask 418 · userTask 247 · subProcess 102 · task 81 · businessRuleTask 31
             callActivity 29 · serviceTask 23 · adHocSubProcess 5 · receiveTask 4 · sendTask 1
gateways     exclusive 85 · parallel 42 · inclusive 40 · eventBased 7
events       end 629 · start 524 · boundary 84 · intermediateCatch 88 · intermediateThrow 34
             timer 78 · signal 89 · error 37 · message 19 · conditional 10
data         dataInput 605 · dataInputAssociation 599 · property 388 · ioSpecification 310
wiring       sequenceFlow 1913 · participant 39 · lane 1
```

The assistant can rename a step and insert a plain task. It cannot create a gateway, an event, a
boundary event, a sub-process, a lane, or a data association.

## The line that actually divides this work

Not "read versus write", and not "DMN versus BPMN". It is **geometry**.

Every edit the assistant makes today is text-in-place: a cell's contents, an attribute, a new
`rule` element. None of them touch the diagram, because none of the things they change *have* a
position. That is why `add_rule` was an afternoon's work — a rule is a row, and a row has no shape.

Creating a decision, a task, a gateway or a connection is a different class of problem. The corpus
carries `DMNShape` 3,035 times, `DMNEdge` 2,456, and `waypoint` 5,033. A new node needs a shape
with sensible bounds; a new connection needs an edge with waypoints that do not cross the diagram.
That is a layout problem, and it is the reason "add a node" is not simply the next tool along.

**The first thing to establish is whether it can be avoided.** If the Kogito editor lays out a
decision that has no `DMNShape` — rather than dropping it from the canvas — then creating elements
costs far less than it appears to, and the ordering below changes. That is one experiment, and it
has not been run.

## Where the value is, per unit of work

1. **Read literal expressions.** Turns 7% coverage of decision logic into most of it, needs no
   geometry, and is read-only so no gate has to change. It is already the top item in
   `05-future-work.md` and this scan is the argument for keeping it there.
2. **Data types.** `itemDefinition` has no diagram presence at all, so creating and editing them —
   including nested structs — is text work. 1,810 occurrences and currently zero support.
3. **BPMN properties.** Documentation, script bodies, timer definitions, assignments. All
   attribute or child-element edits on nodes that already exist, so again no geometry.
4. **Create nodes and connections.** The largest and the one gated on the layout question above.

Items 1–3 are all reachable with the harness as it stands: new tools, the same editor pattern, the
same gates. Item 4 needs something the project does not have yet.

## What does not change

Whatever gets added, the shape stays: the model names a coordinate and a value, deterministic code
writes the XML, and Kogito's own compiler decides whether the result may be offered. Widening what
can be attempted has never meant widening what can be written without checking.
