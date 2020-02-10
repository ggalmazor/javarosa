/*
 * Copyright 2019 Nafundi
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

package org.javarosa.core.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.createTempFile;
import static java.nio.file.Files.delete;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.write;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.util.stream.Collectors.joining;
import static org.javarosa.core.model.instance.TreeReference.INDEX_TEMPLATE;
import static org.javarosa.form.api.FormEntryController.EVENT_BEGINNING_OF_FORM;
import static org.javarosa.form.api.FormEntryController.EVENT_END_OF_FORM;
import static org.javarosa.form.api.FormEntryController.EVENT_GROUP;
import static org.javarosa.form.api.FormEntryController.EVENT_PROMPT_NEW_REPEAT;
import static org.javarosa.form.api.FormEntryController.EVENT_QUESTION;
import static org.javarosa.form.api.FormEntryController.EVENT_REPEAT;
import static org.javarosa.form.api.FormEntryController.EVENT_REPEAT_JUNCTURE;
import static org.javarosa.test.utils.ResourcePathHelper.r;
import static org.javarosa.xpath.expr.XPathPathExpr.INIT_CONTEXT_RELATIVE;
import static org.javarosa.xpath.expr.XPathStep.AXIS_ATTRIBUTE;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.javarosa.core.model.CoreModelModule;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.IFormElement;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.IntegerData;
import org.javarosa.core.model.data.MultipleItemsData;
import org.javarosa.core.model.data.StringData;
import org.javarosa.core.model.data.helper.Selection;
import org.javarosa.core.model.instance.InstanceInitializationFactory;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.services.PrototypeManager;
import org.javarosa.core.services.locale.Localizer;
import org.javarosa.core.util.JavaRosaCoreModule;
import org.javarosa.core.util.XFormsElement;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.debug.Event;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryModel;
import org.javarosa.form.api.FormEntryPrompt;
import org.javarosa.model.xform.XFormsModule;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.expr.XPathNumNegExpr;
import org.javarosa.xpath.expr.XPathNumericLiteral;
import org.javarosa.xpath.expr.XPathPathExpr;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <div style="border: 1px 1px 1px 1px; background-color: #556B2F; color: white; padding: 20px">
 * <b>Warning</b> This class is probably incomplete. If your testing requirements
 * aren't met by this class, please, ask around and let's try to make this tool
 * awesome together.
 * <ul>
 * <li><a href="https://opendatakit.slack.com">OpenDataKit Slack</a></li>
 * <li><a href="https://github.com/opendatakit/javarosa/issues">GitHub issues</a></li>
 * <li><a href="https://forum.opendatakit.org/c/development">Development forum</a></li>
 * </ul>
 * <hr/>
 * </div>
 * <p>
 * This class helps writing JavaRosa tests. It provides two separate APIs:
 * <ul>
 * <li>A static, declarative API that lets the test author to define the state
 * of a form in a given time.</li>
 * <li>A dynamic, imperative API that lets the test author fill the form as the
 * user would do, by controlling the flow while filling questions. These methods
 * return the {@link Scenario} to be able to chain compatible methods.</li>
 * </ul>
 * <p>
 * All the methods that accept a {@link String} xpath argument, support an enhanced
 * version of xpath with the following perks and limitations:
 * <ul>
 * <li>Only supports absolute xpaths</li>
 * <li>Supports adding the index (zero-indexed) of a repeat instance by suffixing it between
 * brackets. Example that would select the fourth instance of the <code>/foo/bar</code>
 * repeat: <code>/foo/bar[3]</code></li>
 * </ul>
 * <p>
 */
// TODO Extract both APIs to two separate contexts so that they can't be mixed, probably best if it's a Scenario steps runner that would have the common .given(setup).when(action).then(assertion)
public class Scenario {
    private static final Logger log = LoggerFactory.getLogger(Scenario.class);
    public static final FormIndex BEGINNING_OF_FORM = FormIndex.createBeginningOfFormIndex();
    private final FormDef formDef;
    private final FormEntryController formEntryController;
    private final EvaluationContext ec;
    private final FormEntryModel model;

    private Scenario(FormDef formDef, FormEntryController formEntryController) {
        this.formDef = formDef;
        this.formEntryController = formEntryController;
        this.ec = formDef.getEvaluationContext();
        this.model = formEntryController.getModel();
    }

    // region Miscelaneous

    /**
     * Returns a TreeReference from the provided xpath string.
     * <p>
     * This method parses the provided xpath string using the
     * XPathParseTool and postprocesses the resulting reference
     * to account for multiplicity predicates.
     * <p>
     * JavaRosa relies on negative multiplicity values to identify special
     * nodes (such as templates for repeat groups), unbound references
     * (to refer to a nodeset), and on positive multiplicity values to
     * specify individual repeat group instances.
     * <p>
     * These multiplicities are declared as numeric predicates such as
     * <code>[2]</code>, which are translated to predicates by the XPath parser.
     * This is problematic because JavaRosa will eventually try to evaluate
     * all predicates declared in references when resolving elements of an
     * instance and nodes won't ever match the predicates used to define
     * multiplicity.
     * <p>
     * For this reason, this method will try to detect these predicates,
     * turn them into multiplicity values, and remove them from the output
     * reference.
     */
    public static TreeReference getRef(String xpath) {
        if (xpath.trim().isEmpty())
            return new TreeReference();
        try {
            TreeReference reference = ((XPathPathExpr) XPathParseTool.parseXPath(xpath)).getReference();
            for (int i = 0; i < reference.size(); i++) {
                Optional<Integer> multiplicity = extractMultiplicityFromPredicate(reference, i);
                if (multiplicity.isPresent()) {
                    reference.setMultiplicity(i, multiplicity.get());
                    reference = reference.removePredicates(i);
                }
            }
            return reference;
        } catch (XPathSyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Prepares the form to answer a new blank instance
     */
    public void newInstance() {
        formDef.initialize(true, new InstanceInitializationFactory());
    }

    /**
     * Sets the language of the form for itext translations
     */
    public void setLanguage(String language) {
        formEntryController.setLanguage(language);
    }

    public EvaluationContext getEvaluationContext() {
        return ec;
    }

    /**
     * Sets a callback that will be called every time there's a DAG event
     */
    public Scenario onDagEvent(Consumer<Event> callback) {
        formDef.setEventNotifier(callback::accept);
        return this;
    }

    /**
     * Returns a new Scenario instance using a new form obtained by
     * serializing and deserializing the form being used by this instance.
     */
    public Scenario serializeAndDeserializeForm() throws IOException, DeserializationException {
        // Initialize serialization
        PrototypeManager.registerPrototypes(JavaRosaCoreModule.classNames);
        PrototypeManager.registerPrototypes(CoreModelModule.classNames);
        new XFormsModule().registerModule();

        // Serialize form in a temp file
        Path tempFile = createTempFile("javarosa", "test");
        formDef.writeExternal(new DataOutputStream(newOutputStream(tempFile)));

        // Create an empty FormDef and deserialize the form into it
        FormDef deserializedFormDef = new FormDef();
        deserializedFormDef.readExternal(
            new DataInputStream(newInputStream(tempFile)),
            PrototypeManager.getDefault()
        );

        delete(tempFile);
        return new Scenario(deserializedFormDef, new FormEntryController(new FormEntryModel(deserializedFormDef)));
    }

    /**
     * Returns the single expanded reference of the provided reference.
     * <p>
     * This method assumes the provided reference will only be expanded
     * to exactly one reference, which is useful to go from unbound
     * references to fully qualified references that wouldn't match existing
     * form indexes otherwise.
     */
    private TreeReference expandSingle(TreeReference reference) {
        List<TreeReference> expandedRefs = ec.expandReference(reference);
        if (expandedRefs.size() != 1)
            throw new RuntimeException("Provided xPath expands to " + expandedRefs.size() + " references. Expecting exactly one expanded reference.");
        return expandedRefs.get(0);
    }

    private boolean refExists(TreeReference reference) {
        return ec.expandReference(reference).size() == 1;
    }

    /**
     * Returns the multiplicity value at the provided step number of the
     * provided reference if defined.
     * <p>
     * Handles special multiplicity textual representations such as <code>[@template]</code>
     */
    private static Optional<Integer> extractMultiplicityFromPredicate(TreeReference reference, int stepNumber) {
        List<XPathExpression> predicates = reference.getPredicate(stepNumber);
        if (predicates == null || predicates.size() != 1)
            return Optional.empty();

        if (isPositiveNumberPredicate(predicates))
            return Optional.ofNullable(predicates.get(0))
                .map(p -> ((XPathNumericLiteral) p).d)
                .map(Double::intValue);

        if (isNegativeNumberPredicate(predicates))
            return Optional.ofNullable(predicates.get(0))
                .map(p -> ((XPathNumNegExpr) p).a)
                .map(p -> ((XPathNumericLiteral) p).d)
                .map(Double::intValue)
                .map(i -> i * -1);

        if (isAtTemplatePredicate(predicates))
            return Optional.of(INDEX_TEMPLATE);

        return Optional.empty();
    }

    /**
     * Detects [0] (example) textual representation of a node's multiplicity as a predicate
     */
    private static boolean isPositiveNumberPredicate(List<XPathExpression> predicates) {
        return predicates.get(0) instanceof XPathNumericLiteral;
    }

    /**
     * Detects [-2] (example) textual representation of a node's multiplicity as a predicate
     */
    private static boolean isNegativeNumberPredicate(List<XPathExpression> predicates) {
        return predicates.get(0) instanceof XPathNumNegExpr && ((XPathNumNegExpr) predicates.get(0)).a instanceof XPathNumericLiteral;
    }

    /**
     * Detects the special case of [@template] textual representation of
     * a node's template multiplicity as a predicate
     */
    private static boolean isAtTemplatePredicate(List<XPathExpression> predicates) {
        return predicates.get(0) instanceof XPathPathExpr
            && ((XPathPathExpr) predicates.get(0)).steps.length == 1
            && ((XPathPathExpr) predicates.get(0)).init_context == INIT_CONTEXT_RELATIVE
            && ((XPathPathExpr) predicates.get(0)).steps[0].axis == AXIS_ATTRIBUTE
            && ((XPathPathExpr) predicates.get(0)).steps[0].name.name.equals("template");
    }

    private FormIndex getIndexOf(TreeReference ref) {
        TreeReference qualifiedRef = expandSingle(ref);
        FormIndex backupIndex = model.getFormIndex();
        silentJump(BEGINNING_OF_FORM);
        FormIndex index = model.getFormIndex();
        do {
            TreeReference refAtIndex = index.getReference();
            if (refAtIndex != null && refAtIndex.equals(qualifiedRef)) {
                silentJump(backupIndex);
                return index;
            }
            index = model.incrementIndex(index);
        } while (index.isInForm());
        silentJump(backupIndex);
        return null;
    }

    public enum AnswerResult {
        OK(0), REQUIRED_BUT_EMPTY(1), CONSTRAINT_VIOLATED(2);

        private final int jrCode;

        AnswerResult(int jrCode) {
            this.jrCode = jrCode;
        }

        public static AnswerResult from(int jrCode) {
            return Stream.of(values())
                .filter(v -> v.jrCode == jrCode)
                .findFirst()
                .orElseThrow(RuntimeException::new);
        }
    }

    // endregion

    // region Initialization of a Scenario

    /**
     * Initializes the Scenario using a form defined using the DSL in XFormsElement
     */
    // TODO Extract the form's name from the provided XFormsElement object to simplify args
    public static Scenario init(String formName, XFormsElement form) throws IOException {
        Path formFile = createTempDirectory("javarosa").resolve(formName + ".xml");
        String xml = form.asXml();
        System.out.println(xml);
        write(formFile, xml.getBytes(UTF_8), CREATE);
        return Scenario.init(formFile);
    }

    /**
     * Initializes the Scenario with provided form filename.
     * <p>
     * A form with the provided filename must exist in the classpath
     */
    public static Scenario init(String formFileName) {
        return init(r(formFileName));
    }

    /**
     * Initializes the Scenario with the form at the provided path
     */
    public static Scenario init(Path formFile) {
        // TODO explain why this sequence of calls
        new XFormsModule().registerModule();
        FormParseInit fpi = new FormParseInit(formFile);
        FormDef formDef = fpi.getFormDef();
        formDef.initialize(true, new InstanceInitializationFactory());
        FormEntryModel formEntryModel = new FormEntryModel(formDef);
        FormEntryController formEntryController = new FormEntryController(formEntryModel);
        return new Scenario(formDef, formEntryController);
    }

    // endregion

    // region Answer a specific question
    // TODO Make more overloads of these methods to have one for each data type using the correct IAnswerData subclass

    /**
     * Answers with a string value the question at the form index
     * corresponding to the provided reference.
     * <p>
     * This method has side-effects:
     * - It will create all the required middle and end repeat group instances
     * - It changes the current form index
     */
    public AnswerResult answer(String xPath, String value) {
        createMissingRepeats(xPath);
        TreeReference ref = getRef(xPath);
        silentJump(getIndexOf(ref));
        return answer(value);
    }

    /**
     * Answers with a list of string values the question at the form index
     * corresponding to the provided reference.
     * <p>
     * This method has side-effects:
     * - It will create all the required middle and end repeat group instances
     * - It changes the current form index
     */
    public AnswerResult answer(String xPath, String... selectionValues) {
        createMissingRepeats(xPath);
        TreeReference ref = getRef(xPath);
        silentJump(getIndexOf(ref));
        return answer(Arrays.asList(selectionValues));
    }

    /**
     * Answers with an integer value the question at the form index
     * corresponding to the provided reference.
     * <p>
     * This method has side-effects:
     * - It will create all the required middle and end repeat group instances
     * - It changes the current form index
     */
    public AnswerResult answer(String xPath, int value) {
        createMissingRepeats(xPath);
        TreeReference ref = getRef(xPath);
        silentJump(getIndexOf(ref));
        return answer(value);
    }

    // endregion

    // region Answer the question at the form index
    // TODO Make more overloads of these methods to have one for each data type using the correct IAnswerData subclass

    /**
     * Answers the question at the form index
     */
    public AnswerResult answer(String value) {
        return answer(new StringData(value));
    }

    /**
     * Answers the question at the form index
     */
    public AnswerResult answer(List<String> values) {
        return answer(new MultipleItemsData(values.stream().map(Selection::new).collect(Collectors.toList())));
    }

    /**
     * Answers the question at the form index
     */
    public AnswerResult answer(int value) {
        return answer(new IntegerData(value));
    }

    /**
     * Answers the question at the form index
     */
    public AnswerResult answer(char value) {
        return answer(new StringData(String.valueOf(value)));
    }

    private AnswerResult answer(IAnswerData data) {
        FormIndex formIndex = model.getFormIndex();
        log.info("Answer {} at {}", data, formIndex.getReference());
        return AnswerResult.from(formEntryController.answerQuestion(formIndex, data, true));
    }

    // endregion

    // region Repeat group manipulation

    /**
     * Removes the repeat instance corresponding to the provided reference
     */
    public Scenario removeRepeat(String xPath) {
        TreeReference reference = expandSingle(getRef(xPath));

        TreeElement group = formDef.getMainInstance().resolveReference(reference);
        FormIndex childIndex = null;
        for (int i = 0; i < group.getNumChildren(); i++) {
            childIndex = getIndexOf(group.getChildAt(i).getRef());
            if (childIndex != null)
                break;
        }
        if (childIndex == null)
            throw new RuntimeException("Can't find an index inside the repeat group you want to remove. Please add some field and a form control.");

        // FormDef.deleteRepeat requires a FormIndex belonging to
        // a descendant of the repeat we want to delete
        formDef.deleteRepeat(childIndex);
        return this;
    }

    /**
     * Creates a new repeat group instance. The form index must be
     * at a create new repeat group question
     */
    public Scenario createNewRepeat() {
        log.info("Create repeat instance {}", model.getFormIndex().getReference());
        formEntryController.newRepeat();
        return this;
    }

    /**
     * Creates a new repeat group instance in the group corresponding
     * to the provided xPath reference
     */
    public Scenario createNewRepeat(String xPath) {
        TreeReference groupRef = getRef(xPath);
        if (!groupRef.isAmbiguous())
            throw new RuntimeException("Provided xPath must be ambiguous");

        // Compute the next multiplicity value counting the existing instances
        TreeReference repeatInstanceRef = groupRef.clone();
        int multiplicity = ec.expandReference(repeatInstanceRef).size();
        repeatInstanceRef.setMultiplicity(repeatInstanceRef.size() - 1, multiplicity);

        return createRepeat(repeatInstanceRef);
    }

    /**
     * Creates a repeat group corresponding to the specific repeat
     * instance reference, creating middle instances if necessary
     * to reach the specified multiplicity.
     */
    private Scenario createRepeat(TreeReference repeatInstanceRef) {
        if (repeatInstanceRef.isAmbiguous())
            throw new RuntimeException("The provided reference can't be ambiguous");

        silentJump(BEGINNING_OF_FORM);
        while (!atTheEndOfForm() && !refExists(repeatInstanceRef))
            if (silentNext() == EVENT_PROMPT_NEW_REPEAT) {
                if (model.getFormIndex().getReference().equals(repeatInstanceRef))
                    // We're in one (probably the first) of the siblings of the
                    // repeat group at the subRef. Create new repeats until the
                    // one want we need is created
                    while (!refExists(repeatInstanceRef))
                        formEntryController.descendIntoNewRepeat();
            }
        if (!refExists(repeatInstanceRef))
            throw new RuntimeException("We couldn't create repeat group instance at " + repeatInstanceRef + ". Check your form and your test");
        return this;
    }

    private void createMissingRepeats(String xPath) {
        FormIndex backupIndex = model.getFormIndex();
        TreeReference reference = getRef(xPath);
        for (int i = 0; i < reference.size(); i++) {
            if (reference.getMultiplicity(i) < 0)
                // This part has no multiplicity, so we don't need to do anything.
                continue;
            createRepeat(reference.getSubReference(i));
        }
        silentJump(backupIndex);
    }

    // endregion

    // region Traversing the form

    /**
     * Jump to the next event.
     * <p>
     * Side effects:
     * - This method updates the form index.
     * - This method leaves log traces
     */
    public int next() {
        int jumpResultCode = formEntryController.stepToNextEvent();
        log.info(humanJumpTrace(jumpResultCode));
        return jumpResultCode;
    }


    /**
     * Jump to the beginning of the form.
     * <p>
     * Side effects:
     * - This method updates the form index.
     * - This method leaves log traces
     */
    public void jumpToBeginningOfForm() {
        jump(BEGINNING_OF_FORM);
    }

    private int silentNext() {
        return formEntryController.stepToNextEvent();
    }

    private int jump(FormIndex index) {
        int jumpResultCode = formEntryController.jumpToIndex(index);
        log.info(humanJumpTrace(jumpResultCode));
        return jumpResultCode;
    }

    private void silentJump(FormIndex indexOf) {
        formEntryController.jumpToIndex(indexOf);
    }

    private String humanJumpTrace(int jumpResultCode) {
        FormIndex formIndex = model.getFormIndex();
        String humanJumpResult = decodeJumpResult(jumpResultCode);
        IFormElement element = formDef.getChild(formIndex);
        String humanLabel = Optional.ofNullable(element.getLabelInnerText()).orElseGet(() -> {
            Localizer localizer = formDef.getLocalizer();
            String textId = element.getTextID();

            if (textId == null || localizer == null) {
                return "";
            }

            return Optional.ofNullable(localizer.getText(textId))
                .map(this::trimToOneLine)
                .orElse("");
        });
        String humanReference = Optional.ofNullable(formIndex.getReference())
            .map(ref -> ref.toString(true, true))
            .orElse("");

        return String.format(
            "Jump to %s%s%s",
            humanJumpResult,
            prefixIfNotEmpty(" ", humanLabel),
            prefixIfNotEmpty(" ref:", humanReference));
    }

    private String prefixIfNotEmpty(String prefix, String text) {
        return text.isEmpty() ? "" : prefix + text;
    }

    private String trimToOneLine(String text) {
        return Stream.of(text.split("\n"))
            .map(String::trim)
            .collect(joining(" "));
    }

    private String decodeJumpResult(int code) {
        switch (code) {
            case EVENT_BEGINNING_OF_FORM:
                return "Beginning of Form";
            case EVENT_END_OF_FORM:
                return "End of Form";
            case EVENT_PROMPT_NEW_REPEAT:
                return "Prompt new Repeat";
            case EVENT_QUESTION:
                return "Question";
            case EVENT_GROUP:
                return "Group";
            case EVENT_REPEAT:
                return "Repeat";
            case EVENT_REPEAT_JUNCTURE:
                return "Repeat Juncture";
        }
        return "Unknown";
    }

    // endregion

    // region Inspect the form index

    public boolean atTheEndOfForm() {
        return model.getFormIndex().isEndOfFormIndex();
    }

    public QuestionDef getQuestionAtIndex() {
        return model.getQuestionPrompt().getQuestion();
    }

    // endregion

    // region Inspect the main instance

    @SuppressWarnings("unchecked")
    public <T extends IAnswerData> T answerOf(String xPath) {
        TreeReference reference = getRef(xPath);
        if (!refExists(reference))
            return null;

        TreeElement element = formDef.getMainInstance().resolveReference(reference);
        return element != null ? (T) element.getValue() : null;
    }

    public int countRepeatInstancesOf(String xPath) {
        TreeReference reference = getRef(xPath);
        if (!reference.isAmbiguous())
            throw new RuntimeException("Provided xPath must be ambiguous");

        return ec
            .expandReference(reference)
            .size();
    }

    /**
     * Returns the list of choices of the &lt;select&gt; or &lt;select1&gt; form controls.
     * <p>
     * This method ensures that any dynamic choice lists are populated to reflect the status
     * of the form (already answered questions, etc.).
     */
    public List<SelectChoice> choicesOf(String xPath) {
        TreeReference reference = expandSingle(getRef(xPath));

        FormEntryPrompt questionPrompt = model.getQuestionPrompt(getIndexOf(reference));
        // This call triggers the correct population of dynamic choices.
        questionPrompt.getAnswerValue();
        QuestionDef control = questionPrompt.getQuestion();
        return control.getChoices() == null
            // If the (static) choices is null, that means there is an itemset and choices are dynamic
            // ItemsetBinding.getChoices() will work because we've called questionPrompt.getAnswerValue()
            ? control.getDynamicChoices().getChoices()
            : control.getChoices();
    }

    // endregion
}
