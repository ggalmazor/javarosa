/*
 * Copyright 2020 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.javarosa.core.model;


import static guru.nidi.graphviz.attribute.Color.DARKSLATEGRAY;
import static guru.nidi.graphviz.attribute.Color.DEEPSKYBLUE;
import static guru.nidi.graphviz.attribute.Color.FIREBRICK;
import static guru.nidi.graphviz.attribute.Color.GOLDENROD;
import static guru.nidi.graphviz.attribute.Color.GREY;
import static guru.nidi.graphviz.attribute.Color.MEDIUMSLATEBLUE;
import static guru.nidi.graphviz.attribute.Color.RED;
import static guru.nidi.graphviz.attribute.Records.rec;
import static guru.nidi.graphviz.attribute.Records.turn;
import static guru.nidi.graphviz.engine.Rasterizer.builtIn;
import static guru.nidi.graphviz.model.Factory.graph;
import static guru.nidi.graphviz.model.Factory.node;
import static guru.nidi.graphviz.model.Factory.to;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.Collections.emptyList;
import static java.util.Collections.indexOfSubList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.Matchers.is;
import static org.javarosa.core.test.AnswerDataMatchers.intAnswer;
import static org.javarosa.core.test.AnswerDataMatchers.stringAnswer;
import static org.javarosa.test.utils.ResourcePathHelper.r;
import static org.junit.Assert.assertThat;

import guru.nidi.graphviz.attribute.Color;
import guru.nidi.graphviz.attribute.Records;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.model.Link;
import guru.nidi.graphviz.model.Node;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.test.Scenario;
import org.javarosa.debug.Event;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.graph.ConditionEdge;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.RecalculateEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WhoVATest {
    private static final Logger log = LoggerFactory.getLogger(WhoVATest.class);

    @Test
    public void graphviz() throws IOException {
        Scenario scenario = Scenario.init(r("whova_form.xml"));

        FormDef formDef = scenario.getFormDef();
        TriggerableDag dag = formDef.dagImpl;

        Set<String> vertices = Stream.of(
            dag.allTriggerables.stream().map(QuickTriggerable::getTargets).flatMap(Collection::stream).map(TreeReference::toString),
            dag.allTriggerables.stream().map(QuickTriggerable::getTriggers).flatMap(Collection::stream).map(TreeReference::toString)
        ).flatMap(i -> i).sorted().collect(toCollection(LinkedHashSet::new));

        IdSequence idSequence = new IdSequence();

        Map<String, Node> nodes = vertices.stream().collect(toMap(
            i -> i,
            i -> {
                String id = idSequence.next();
                AtomicInteger seq = new AtomicInteger(1);
                List<String> recordParts = Stream.of(i.substring(1).split("/")).map(part -> rec(id + seq.getAndIncrement(), part)).collect(toList());
                return node(id).with(Records.of(turn(recordParts.toArray(new String[]{}))));
            }));

        Set<Node> completeLinks = dag.triggerablesPerTrigger.entrySet().stream()
            .flatMap(entry -> entry
                .getValue()
                .stream()
                .flatMap(triggerable -> triggerable.getTargets().stream()
                    .map(targetRef -> buildLink(
                        nodes,
                        triggerable,
                        entry.getKey().toString(),
                        targetRef.toString()
                    ))
                )
            )
            .collect(toSet());

        Set<Node> calculateLinks = dag.triggerablesPerTrigger.entrySet().stream()
            .flatMap(entry -> entry
                .getValue()
                .stream()
                .filter(QuickTriggerable::isRecalculate)
                .flatMap(triggerable -> triggerable.getTargets().stream()
                    .map(targetRef -> buildLink(
                        nodes,
                        triggerable,
                        entry.getKey().toString(),
                        targetRef.toString()
                    ))
                )
            )
            .collect(toSet());

        Set<Node> conditionLinks = dag.triggerablesPerTrigger.entrySet().stream()
            .flatMap(entry -> entry
                .getValue()
                .stream()
                .filter(QuickTriggerable::isCondition)
                .flatMap(triggerable -> triggerable.getTargets().stream()
                    .map(targetRef -> buildLink(
                        nodes,
                        triggerable,
                        entry.getKey().toString(),
                        targetRef.toString()
                    ))
                )
            )
            .collect(toSet());

        Graphviz.fromGraph(graph("complete").directed()
            .graphAttr().with("ratio", 1)
            .with(completeLinks.toArray(new Node[]{})))
            .rasterize(builtIn("pdf"))
            .toFile(new File("/tmp/dag-complete.pdf"));

        Graphviz.fromGraph(graph("calculates").directed()
            .graphAttr().with("ratio", 1)
            .with(calculateLinks.toArray(new Node[]{})))
            .rasterize(builtIn("pdf"))
            .toFile(new File("/tmp/dag-calculates.pdf"));

        Graphviz.fromGraph(graph("conditions").directed()
            .graphAttr().with("ratio", 1)
            .with(conditionLinks.toArray(new Node[]{})))
            .rasterize(builtIn("pdf"))
            .toFile(new File("/tmp/dag-conditions.pdf"));
    }

    @Test
    public void jgrapht() throws IOException {
        Scenario scenario = Scenario.init(r("whova_form.xml"));

        FormDef formDef = scenario.getFormDef();
        TriggerableDag dag = formDef.dagImpl;

        Set<String> vertices = Stream.of(
            dag.allTriggerables.parallelStream().map(QuickTriggerable::getTargets).flatMap(Collection::stream).map(TreeReference::toString),
            dag.allTriggerables.parallelStream().map(QuickTriggerable::getTriggers).flatMap(Collection::stream).map(TreeReference::toString)
        ).flatMap(i -> i).sorted().collect(toCollection(LinkedHashSet::new));

        IdSequence idSequence = new IdSequence();

        Map<String, Node> nodes = vertices.parallelStream().collect(toMap(
            i -> i,
            i -> {
                String id = idSequence.next();
                AtomicInteger seq = new AtomicInteger(1);
                List<String> recordParts = Stream.of(i.substring(1).split("/"))
                    .map(part -> rec(id + seq.getAndIncrement(), part))
                    .collect(toList());
                return node(id).with(Records.of(turn(recordParts.toArray(new String[]{}))));
            }));

        Graph<String, DefaultEdge> completeGraph = new SimpleDirectedGraph<>(DefaultEdge.class);
        dag.triggerablesPerTrigger.forEach((key, value) -> {
            String source = key.toString();
            completeGraph.addVertex(source);
            value.forEach(triggerable -> triggerable.getTargets().forEach(targetRef -> {
                String target = targetRef.toString();
                completeGraph.addVertex(target);
                completeGraph.addEdge(
                    source,
                    target,
                    triggerable.isRecalculate() ? new RecalculateEdge() : new ConditionEdge()
                );
            }));
        });

        Graph<String, DefaultEdge> conditionsGraph = new SimpleDirectedGraph<>(DefaultEdge.class);
        completeGraph.edgeSet().stream()
            .filter(edge -> edge instanceof ConditionEdge)
            .map(i -> ((ConditionEdge) i))
            .forEach(edge -> {
                String source = conditionsGraph.getEdgeSource(edge);
                String target = conditionsGraph.getEdgeTarget(edge);
                conditionsGraph.addVertex(source);
                conditionsGraph.addVertex(target);
                conditionsGraph.addEdge(source, target, edge);
            });

        Graph<String, DefaultEdge> recalculatesGraph = new SimpleDirectedGraph<>(DefaultEdge.class);
        completeGraph.edgeSet().stream()
            .filter(edge -> edge instanceof RecalculateEdge)
            .map(i -> ((RecalculateEdge) i))
            .forEach(edge -> {
                String source = conditionsGraph.getEdgeSource(edge);
                String target = conditionsGraph.getEdgeTarget(edge);
                recalculatesGraph.addVertex(source);
                recalculatesGraph.addVertex(target);
                recalculatesGraph.addEdge(source, target, edge);
            });

        AllDirectedPaths<String, DefaultEdge> paths = new AllDirectedPaths<>(recalculatesGraph);
        Set<String> recalculateVertices = nodes.keySet().parallelStream()
            .filter(recalculatesGraph::containsVertex)
            .collect(toSet());
        LinkedHashSet<GraphPath<String, DefaultEdge>> allPathsOrdered = recalculateVertices.parallelStream()
            .flatMap(source ->
                recalculateVertices.parallelStream().flatMap(target ->
                    paths.getAllPaths(source, target, true, null).parallelStream()))
            .sorted((GraphPath<String, DefaultEdge> path1, GraphPath<String, DefaultEdge> path2) -> Integer.compare(path2.getLength(), path1.getLength()))
            .collect(toCollection(LinkedHashSet::new));

        Set<GraphPath<String, DefaultEdge>> simplifiedList = allPathsOrdered.parallelStream()
            .filter(path -> {
                List<String> vertexList = path.getVertexList();
                return allPathsOrdered.parallelStream().allMatch(candidateWrapperPath ->
                    path == candidateWrapperPath || indexOfSubList(candidateWrapperPath.getVertexList(), vertexList) == -1
                );
            })
            .collect(toCollection(LinkedHashSet::new));

        Map<Integer, List<GraphPath<String, DefaultEdge>>> bySizes = simplifiedList.parallelStream().collect(groupingBy(GraphPath::getLength));


        Map<Integer, List<GraphPath<String, DefaultEdge>>> byExtendedSizes = simplifiedList.parallelStream().collect(groupingBy(path -> {
            int pathSize = path.getLength();
            int totalConditions = path.getVertexList().stream()
                .filter(conditionsGraph::containsVertex)
                .mapToInt(vertex -> conditionsGraph.outgoingEdgesOf(vertex).size())
                .sum();
            return pathSize + totalConditions;
        }));

        System.out.println("\"Chain length\"\t\"Paths\"");
        for (int i = 1, maxI = bySizes.keySet().stream().mapToInt(n -> n).max().orElse(0); i <= maxI; i++) {
            System.out.println(String.format("%d\t%d", i, bySizes.getOrDefault(i, emptyList()).size()));
        }

        System.out.println();
        System.out.println();

        System.out.println("\"Chain length\"\t\"Paths\"");
        for (int i = 1, maxI = byExtendedSizes.keySet().stream().mapToInt(n -> n).max().orElse(0); i <= maxI; i++) {
            System.out.println(String.format("%d\t%d", i, byExtendedSizes.getOrDefault(i, emptyList()).size()));
        }

        Set<Node> longestChainLinks = new HashSet<>();
        List<String> longestCalculateChainRefs = byExtendedSizes.get(152).get(0).getVertexList();
        for (int i = 0; i < longestCalculateChainRefs.size() - 1; i++) {
            Node source = nodes.get(longestCalculateChainRefs.get(i)).with(RED).with("pos", String.format("0,%d!", i));
            Node target = nodes.get(longestCalculateChainRefs.get(i + 1)).with(RED).with("pos", String.format("0,%d!", i + 1));
            longestChainLinks.add(source.link(to(target).with(RED)));
        }
        longestCalculateChainRefs.forEach(calculateSource -> completeGraph.outgoingEdgesOf(calculateSource).forEach(edge -> {
            Node source = nodes.get(calculateSource).with(RED);
            Node target = nodes.get(completeGraph.getEdgeTarget(edge)).with(GREY);
            Link to = to(target).with(GREY);
            Node link = source.link(to);
            longestChainLinks.add(link);
        }));

        Graphviz.fromGraph(graph("complete").directed()
            .graphAttr().with("ratio", 1)
            .with(longestChainLinks.toArray(new Node[]{})))
            .rasterize(builtIn("pdf"))
            .toFile(new File("/tmp/dag-longest-chain.pdf"));
    }

    @Test
    public void traverse_form_through_path_including_longest_evaluation_chain() throws IOException {
        List<String> jumps = new ArrayList();
        List<Event> events = new ArrayList();
        Scenario scenario = Scenario.init(r("whova_form.xml"))
            .onDagEvent(events::add)
            .onJump(jumps::add);

        scenario.next(13);

        // region Give consent to unblock the rest of the form
        // (Id10013) [Did the respondent give consent?] ref:/data/respondent_backgr/Id10013
        scenario.next();
        scenario.answer("yes");
        // endregion

        scenario.next(5);

        // region Info on deceased
        // (Id10019) What was the sex of the deceased? ref:/data/consented/deceased_CRVS/info_on_deceased/Id10019
        scenario.next();
        scenario.answer("female");
        // (Id10020) Is the date of birth known? ref:/data/consented/deceased_CRVS/info_on_deceased/Id10020
        scenario.next();
        scenario.answer("yes");
        // (Id10021) When was the deceased born? ref:/data/consented/deceased_CRVS/info_on_deceased/Id10021
        scenario.next();
        scenario.answer(LocalDate.parse("1998-01-01"));
        // (Id10022) Is the date of death known? ref:/data/consented/deceased_CRVS/info_on_deceased/Id10022
        scenario.next();
        scenario.answer("yes");
        // (Id10021) When was the deceased born? ref:/data/consented/deceased_CRVS/info_on_deceased/Id10021
        scenario.next();
        scenario.answer(LocalDate.parse("2018-01-01"));

        // Sanity check about age and isAdult field
        assertThat(scenario.answerOf("/data/consented/deceased_CRVS/info_on_deceased/ageInDays"), is(intAnswer(7305)));
        assertThat(scenario.answerOf("/data/consented/deceased_CRVS/info_on_deceased/isAdult"), is(stringAnswer("1")));
        assertThat(scenario.answerOf("/data/consented/deceased_CRVS/info_on_deceased/isNeonatal"), is(stringAnswer("0")));

        // Skip a bunch of non yes/no questions
        scenario.next(11);

        // Answer no to the rest of questions
        IntStream.range(0, 23).forEach(n -> {
            scenario.next();
            if (scenario.atQuestion())
                scenario.answer("no");
        });
        // endregion

        // region Signs and symptoms - fever
        // (Id10147) Did (s)he have a fever? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10147
        scenario.next();
        scenario.answer("yes");
        // (Id10148_units) How long did the fever last? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10148_units
        scenario.next();
        scenario.answer("days");
        // (Id10148_b) [Enter how long the fever lasted in days]: ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10148_b
        scenario.next();
        scenario.answer(30);
        // (Id10149) Did the fever continue until death? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10149
        scenario.next();
        scenario.answer("yes");
        // (Id10150) How severe was the fever? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10150
        scenario.next();
        scenario.answer("severe");
        // (Id10151) What was the pattern of the fever? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10151
        scenario.next();
        scenario.answer("nightly");
        // endregion

        // region Answer "no" to some symptons
        IntStream.range(0, 40).forEach(n -> {
            scenario.next();
            if (scenario.atQuestion())
                scenario.answer("no");
        });
        // endregion

        // region Signs and symptoms - lumps
        // (Id10253) Did (s)he have any lumps? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10253
        scenario.next();
        scenario.answer("yes");
        // (Id10254) Did (s)he have any lumps or lesions in the mouth? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10254
        scenario.next();
        scenario.answer("yes");
        // (Id10255) Did (s)he have any lumps on the neck? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10255
        scenario.next();
        scenario.answer("yes");
        // (Id10256) Did (s)he have any lumps on the armpit? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10256
        scenario.next();
        scenario.answer("yes");
        // (Id10257) Did (s)he have any lumps on the groin? ref:/data/consented/illhistory/signs_symptoms_final_illness/Id10257
        scenario.next();
        scenario.answer("yes");
        // endregion

        // region Answer "no" to the rest of the form
        while (!scenario.atTheEndOfForm()) {
            scenario.next();
            if (scenario.atQuestion())
                scenario.answer("no");
        }
        // endregion


        /*
        List<String> rStream = events.stream()
            .flatMap(e -> e.evaluationResults.stream().map(er -> {
                if (er.triggerable != null)
                    return er.triggerable.toString();
                else if (er.constraint != null)
                    return "Validate " + er.constraintRef.toString() + " with " + er.constraint.constraint.xpath;
                else
                    return e.getMessage() + " " + er.toString();
            }))
            .collect(toList());
        Files.write(Paths.get("/tmp/cocotero.log"), rStream, CREATE, TRUNCATE_EXISTING);
         */

        Files.write(Paths.get("/tmp/cocotero_jumps.log"), jumps, CREATE, TRUNCATE_EXISTING);
    }

    public static Node buildLink(Map<String, Node> index, QuickTriggerable triggerable, String sourcePath, String targetPath) {
        return index.get(sourcePath).link(to(index.get(targetPath)).with(getColor(triggerable)));
    }

    public static Color getColor(QuickTriggerable triggerable) {
        String triggerableString = triggerable.toString();
        if (triggerableString.startsWith("Make relevant"))
            return DEEPSKYBLUE;
        if (triggerableString.startsWith("Enable"))
            return GOLDENROD;
        if (triggerableString.startsWith("Lock"))
            return DARKSLATEGRAY;
        if (triggerableString.startsWith("Require"))
            return MEDIUMSLATEBLUE;
        return FIREBRICK;
    }

    static class IdSequence {
        private int mayorId = 65; // 65:A - 90:Z
        private int minorId = 65; // 65:A - 90:Z

        private String next() {
            String id = String.format("%s%s", (char) mayorId, (char) minorId);
            minorId++;
            if (minorId > 90) {
                minorId = 65;
                mayorId++;
            }
            if (mayorId > 90)
                throw new RuntimeException("Id overflow. We need another letter");
            return id;
        }
    }

}
