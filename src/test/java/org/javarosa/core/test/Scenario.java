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
import static java.util.stream.Collectors.toList;
import static org.javarosa.core.model.instance.TreeReference.CONTEXT_ABSOLUTE;
import static org.javarosa.core.model.instance.TreeReference.INDEX_TEMPLATE;
import static org.javarosa.core.model.instance.TreeReference.REF_ABSOLUTE;
import static org.javarosa.core.model.instance.utils.TreeElementNameComparator.elementMatchesName;
import static org.javarosa.form.api.FormEntryController.EVENT_BEGINNING_OF_FORM;
import static org.javarosa.form.api.FormEntryController.EVENT_END_OF_FORM;
import static org.javarosa.form.api.FormEntryController.EVENT_GROUP;
import static org.javarosa.form.api.FormEntryController.EVENT_PROMPT_NEW_REPEAT;
import static org.javarosa.form.api.FormEntryController.EVENT_QUESTION;
import static org.javarosa.form.api.FormEntryController.EVENT_REPEAT;
import static org.javarosa.form.api.FormEntryController.EVENT_REPEAT_JUNCTURE;
import static org.javarosa.test.utils.ResourcePathHelper.r;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.javarosa.core.model.CoreModelModule;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.IFormElement;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.data.DateData;
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
    private final FormDef formDef;
    private final FormEntryController formEntryController;
    private Consumer<String> jumpCallback = __ -> {
    };

    private Scenario(FormDef formDef, FormEntryController formEntryController) {
        this.formDef = formDef;
        this.formEntryController = formEntryController;
    }

    public static Scenario init(String formName, XFormsElement form) throws IOException {
        Path formFile = createTempDirectory("javarosa").resolve(formName + ".xml");
        String xml = form.asXml();
        System.out.println(xml);
        write(formFile, xml.getBytes(UTF_8), CREATE);
        return Scenario.init(formFile);
    }

    /**
     * Creates and prepares the test scenario loading and parsing the given form
     */
    public static Scenario init(String formFileName) {
        return init(r(formFileName));
    }

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

    /**
     * Sets the value of the element located at the given xPath in the main instance.
     * <p>
     * This method ensures that any required downstream change after the given value
     * is set is triggered.
     */
    // TODO Make more of these, one for each data type, and use the correct IAnswerData type
    public void answer(String xPath, String value) {
        // the xPath could include repeat group indexes, meaning that we expect
        // some repeat group to exists. We check that and create missing repeats
        // where they're needed.
        createMissingRepeats(xPath);

        // The xPath must match a model element. This ensures we can resolve it.
        TreeElement element = Objects.requireNonNull(resolve(xPath));

        // We wrap the given value and set it through the formDef, which triggers
        // any downstream change in dependant elements (elements that have a
        // calculate formula depending on this field, or itemsets rendering this
        // field's value as a choice)
        formDef.setValue(new StringData(value), element.getRef(), true);
    }

    /**
     * Sets the value of the element located at the given xPath in the main instance to a multiple select selection
     * created from the given values.
     */
    public void answer(String xPath, String... selectionValues) {
        createMissingRepeats(xPath);
        TreeElement element = Objects.requireNonNull(resolve(xPath));

        List<Selection> selections = Arrays.stream(selectionValues).map(Selection::new).collect(toList());
        formDef.setValue(new MultipleItemsData(selections), element.getRef(), true);
    }

    /**
     * Sets the value of the element located at the given xPath in the main instance to the given integer value.
     *
     * @see #answer(String, String)
     */
    public void answer(String xPath, int value) {
        createMissingRepeats(xPath);
        TreeElement element = Objects.requireNonNull(resolve(xPath));
        formDef.setValue(new IntegerData(value), element.getRef(), true);
    }

    /**
     * Jumps to the first question with the given name.
     */
    public Scenario jumpToFirst(String name) {
        jumpToFirstQuestionWithName(name);
        return this;
    }

    private AnswerResult answer(IAnswerData data) {
        FormIndex formIndex = formEntryController.getModel().getFormIndex();
        IFormElement child = formDef.getChild(formIndex);
        log.info("Answer {} at {}", data, prefixIfNotEmpty("ref:", getReference(child)));
        return AnswerResult.from(formEntryController.answerQuestion(formIndex, data, true));
    }

    public AnswerResult answer(String value) {
        return answer(new StringData(value));
    }

    public AnswerResult answer(int value) {
        return answer(new IntegerData(value));
    }

    public AnswerResult answer(char value) {
        return answer(new StringData(String.valueOf(value)));
    }

    public AnswerResult answer(LocalDate value) {
        return answer(new DateData(Date.from(value.atStartOfDay().atOffset(OffsetDateTime.now().getOffset()).toInstant())));
    }

    /**
     * Jumps to next event
     */
    public void next() {
        int i = formEntryController.stepToNextEvent();
        String jumpResult = decodeJumpResult(i);
        FormIndex formIndex = formEntryController.getModel().getFormIndex();
        IFormElement child = formDef.getChild(formIndex);
        String humanFriendlyTrace = String.format(
            "Jump to %s%s%s%s",
            jumpResult,
            prefixIfNotEmpty(" ", getLabel(child)),
            prefixIfNotEmpty(" ", getIText(child)),
            prefixIfNotEmpty(" ref:", getReference(child)));
        jumpCallback.accept(humanFriendlyTrace);
        log.info(humanFriendlyTrace);
    }

    public String prefixIfNotEmpty(String prefix, String text) {
        return text.isEmpty() ? "" : prefix + text;
    }

    public String getReference(IFormElement child) {
        try {
            return Optional.ofNullable(child.getBind())
                .map(ref -> (TreeReference) ref.getReference())
                .map(Object::toString)
                .orElse("");
        } catch (RuntimeException e) {
            return "";
        }

    }

    public String getLabel(IFormElement child) {
        return Optional.ofNullable(child.getLabelInnerText()).orElse("");
    }

    public String getIText(IFormElement element) {
        Localizer localizer = formDef.getLocalizer();
        String textId = element.getTextID();

        if (textId == null || localizer == null)
            return "";
        return Optional.ofNullable(localizer.getText(textId))
            .map(this::oneLineTrim)
            .orElse("");
    }

    private String oneLineTrim(String text) {
        return Stream.of(text.split("\n"))
            .map(String::trim)
            .collect(joining(" "));
    }

    /**
     * Jumps to next event with the given name
     */
    public Scenario next(String name) {
        next();
        TreeReference reference = formEntryController.getModel().getFormIndex().getReference();
        String xpath = reference.toString(true, true);
        jump(getIndexOf(xpath + "/" + name));
        return this;
    }

    /**
     * Returns true when the index is at the end of the form, false otherwise
     */
    public boolean atTheEndOfForm() {
        return formEntryController.getModel().getFormIndex().isEndOfFormIndex();
    }

    /**
     * Returns the value of the element located at the given xPath in the main instance.
     * Answers live in the main instance of a form. We will traverse the main
     * instance's tree of elements recursively using the xPath as a guide of
     * steps.
     * <p>
     * The starting point will be the NULL node parent of main instance's root,
     * which corresponds to the root "/" xPath.
     * <p>
     * Note that the formDef.getMainInstance().getRoot() call can be misleading
     * because it would return an element corresponding to the xPath "/data"
     * ("data" is commonly used as the main instance's xml tag), not the root
     * element.
     */
    @SuppressWarnings("unchecked")
    public <T extends IAnswerData> T answerOf(String xPath) {
        // Since we start searching from "/", we make the input xPath relative to that
        String relativeXPath = xPath.startsWith("/") ? xPath.substring(1) : xPath;

        // We call the recursive resolve algorithm and get the element
        TreeElement element = resolve(getRootElement(), relativeXPath);

        // Return the value if the element exists, otherwise return null
        return element != null ? (T) element.getValue() : null;
    }

    public List<TreeElement> repeatInstancesOf(String xPath) {
        // Since we start searching from "/", we make the input xPath relative to that
        String relativeXPath = xPath.startsWith("/") ? xPath.substring(1) : xPath;

        String parentXPath = pop(relativeXPath);
        String repeatName = tailPart(xPath);

        // We call the recursive resolve algorithm and get the element
        TreeElement parent = resolve(getRootElement(), parentXPath);

        if (parent == null)
            throw new RuntimeException("The parent element at " + parentXPath + " doesn't exist");

        List<TreeElement> children = new ArrayList<>();
        for (int i = 0; i < parent.getNumChildren(); i++) {
            TreeElement child = parent.getChildAt(i);
            if (child.getMultiplicity() != INDEX_TEMPLATE && child.getName().equals(repeatName))
                children.add(child);
        }
        return children;
    }

    /**
     * Returns the list of choices of the &lt;select&gt; or &lt;select1&gt; form controls.
     * This method ensures that any dynamic choice lists are populated to reflect the status
     * of the form (already answered questions, etc.).
     */
    public List<SelectChoice> choicesOf(String xPath) {
        FormEntryPrompt questionPrompt = formEntryController.getModel().getQuestionPrompt(getIndexOf(xPath));
        // This call triggers the correct population of dynamic choices.
        questionPrompt.getAnswerValue();
        QuestionDef control = questionPrompt.getQuestion();
        return control.getChoices() == null
            // If the (static) choices is null, that means there is an itemset and choices are dynamic
            // ItemsetBinding.getChoices() will work because we've called questionPrompt.getAnswerValue()
            ? control.getDynamicChoices().getChoices()
            : control.getChoices();
    }

    /**
     * Prepare a new (main) instance in the form, to simulate the user starting to fill a
     * new form.
     */
    public void newInstance() {
        formDef.initialize(true, new InstanceInitializationFactory());
    }

    public void setLanguage(String language) {
        formEntryController.setLanguage(language);
    }

    public void createMissingRepeats(String xPath) {
        // We will be looking to the parts in the xPath from left to right.
        // xPath.substring(1) makes the first "/" char go away, giving us an xPath relative to the root
        List<String> parts = Arrays.asList(xPath.substring(1).split("/"));
        String currentXPath = "";
        do {
            String nextPart = parts.get(0);
            // nextName holds the next part's name, excluding the multiplicity suffix if it exists.
            String nextName = parseName(nextPart);
            String nextXPath = currentXPath + "/" + nextPart;

            if (isRepeatXPath(nextXPath) && !elementExists(nextXPath)) {
                // Jumping to the repeat instance for [0] always works because formEntryController.descendIntoNewRepeat() deals with it:
                // - if the [0] doesn't exists, it creates it
                // - if the [0] exists, looks for the next sequential repeat and creates it
                jump(getIndexOf((currentXPath + "/" + nextName) + "[0]"));
                formEntryController.descendIntoNewRepeat();
            }
            // Shift the first part of the list, reset the current xPath and loop
            parts = parts.subList(1, parts.size());
            currentXPath = nextXPath;
        } while (!parts.isEmpty());
    }

    private boolean elementExists(String xPath) {
        return resolve(xPath) != null;
    }

    private static boolean isRepeatXPath(String xPath) {
        // Returns true if the last xPath part contains a multiplicity suffix like "[2]"
        return xPath.substring(xPath.lastIndexOf("/")).contains("[");
    }

    /**
     * Returns an absolute reference of the given xPath taking multiplicity of each
     * xPath part into account.
     */
    public static TreeReference absoluteRef(String xPath) {
        TreeReference tr = new TreeReference();
        tr.setRefLevel(REF_ABSOLUTE);
        tr.setContext(CONTEXT_ABSOLUTE);
        tr.setInstanceName(null);
        Arrays.stream(xPath.split("/"))
            .filter(s -> !s.isEmpty())
            .forEach(s -> tr.add(parseName(s), parseMultiplicity(s)));
        return tr;
    }

    private static String parseName(String xPathPart) {
        return xPathPart.contains("[") ? xPathPart.substring(0, xPathPart.indexOf("[")) : xPathPart;
    }

    private static int parseMultiplicity(String xPathPart) {
        return xPathPart.contains("[") ? Integer.parseInt(xPathPart.substring(xPathPart.indexOf("[") + 1, xPathPart.indexOf("]"))) : 0;
    }

    /**
     * Returns an index to the given xPath. It uses an absolute reference to
     * the given xPath and traverses all existing indexes until one of them
     * matches the reference.
     * <p>
     * This is possible because the {@link TreeReference#equals(Object)} can
     * deal with absolute and relative references.
     * <p>
     * Returns null if the reference is not found.
     */
    private FormIndex getIndexOf(String xPath) {
        return getIndexOf(absoluteRef(xPath));
    }

    private FormIndex getIndexOf(TreeReference ref) {
        jump(FormIndex.createBeginningOfFormIndex());
        FormEntryModel model = formEntryController.getModel();
        FormIndex index = model.getFormIndex();
        do {
            if (index.getReference() != null && index.getReference().equals(ref))
                return index;
            index = model.incrementIndex(index);
        } while (index.isInForm());
        return null;
    }


    /**
     * Returns the element corresponding to the given xPath.
     * <p>
     * The starting point will be the NULL node parent of main instance's root,
     * which corresponds to the root "/" xPath.
     * <p>
     * Note that the formDef.getMainInstance().getRoot() call can be misleading
     * because it would return an element corresponding to the xPath "/data"
     * ("data" is commonly used as the main instance's xml tag), not the root
     * element.
     */
    private TreeElement resolve(String xPath) {
        // Get the real root element
        TreeElement root = getRootElement();
        // Since we start searching from "/", we make the input xPath relative to that
        String relativeXPath = xPath.startsWith("/") ? xPath.substring(1) : xPath;

        return resolve(root, relativeXPath);
    }

    private TreeElement getRootElement() {
        return (TreeElement) formDef.getMainInstance().getRoot().getParent();
    }

    /**
     * Returns the element corresponding to the given xPath.
     * <p>
     * It does so by recursively traversing children of the given element and calling
     * {@link TreeElement#getChild(String, int)} on them.
     */
    private TreeElement resolve(TreeElement element, String xPath) {
        List<String> parts = Arrays.asList(xPath.split("/"));
        String firstPart = parts.get(0);
        TreeElement nextElement = element.getChild(parseName(firstPart), parseMultiplicity(firstPart));

        // Return null when a child with the given name and multiplicity doesn't exist.
        if (nextElement == null)
            return null;

        // If there are more parts to analyze, call recursively on child
        if (parts.size() > 1)
            return resolve(nextElement, shift(xPath));

        // If this is the last part in the xPath, we have the element we're looking for
        return nextElement;
    }

    private static String shift(String xPath) {
        List<String> parts = Arrays.asList(xPath.split("/"));
        return String.join("/", parts.subList(1, parts.size()));
    }

    private static String pop(String xPath) {
        List<String> parts = Arrays.asList(xPath.split("/"));
        return String.join("/", parts.subList(0, parts.size() - 1));
    }

    private static String tailPart(String xPath) {
        List<String> parts = Arrays.asList(xPath.split("/"));
        return parts.get(parts.size() - 1);
    }

    private Optional<TreeElement> getFirstDescendantWithName(TreeElement node, String name) {
        if (isNotRoot(node) && isNotTemplate(node) && elementMatchesName(node, name))
            return Optional.of(node);

        List<TreeElement> nonTemplateChildren = childrenOf(node).stream()
            .filter(this::isNotTemplate)
            .collect(toList());

        for (TreeElement child : nonTemplateChildren) {
            Optional<TreeElement> result = getFirstDescendantWithName(child, name);
            if (result.isPresent())
                return result;
        }

        return Optional.empty();
    }

    private List<TreeElement> childrenOf(TreeElement node) {
        List<TreeElement> children = new ArrayList<>(node.getNumChildren());
        for (int i = 0, max = node.getNumChildren(); i < max; i++) {
            children.add(node.getChildAt(i));
        }
        return children;
    }

    private boolean isNotRoot(TreeElement node) {
        return node.getName() != null;
    }

    private boolean isNotTemplate(TreeElement node) {
        return node.getMultiplicity() != TreeReference.INDEX_TEMPLATE;
    }

    private void jumpToFirstQuestionWithName(String name) {
        TreeReference ref = getFirstDescendantWithName(getRootElement(), name)
            .map(TreeElement::getRef)
            .orElseThrow(() -> new IllegalArgumentException("No question with name " + name + " found"));
        jump(getIndexOf(ref));
    }

    private void jump(FormIndex indexOf) {
        int result = formEntryController.jumpToIndex(indexOf);
        log.debug(prefixIfNotEmpty("Jumped to ", decodeJumpResult(result)));
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

    public Scenario removeRepeat(String xpath) {
        formDef.deleteRepeat(getIndexOf(xpath));
        return this;
    }

    public void createNewRepeat() {
        FormIndex formIndex = formEntryController.getModel().getFormIndex();
        IFormElement child = formDef.getChild(formIndex);
        String reference = "";
        try {
            reference = Optional.ofNullable(child.getBind()).map(idr -> (TreeReference) idr.getReference()).map(Object::toString).map(s -> prefixIfNotEmpty("ref:", s)).orElse("");
        } catch (RuntimeException e) {
            // Do nothing. Probably "method not implemented" in FormDef.getBind()
        }

        log.info("Create repeat instance {}", reference);
        formEntryController.newRepeat();
    }

    public EvaluationContext getEvaluationContext() {
        return formDef.getEvaluationContext();
    }

    public QuestionDef getQuestionAtIndex() {
        return formEntryController.getModel().getQuestionPrompt().getQuestion();
    }

    public FormDef getFormDef() {
        return formDef;
    }

    public void next(int amount) {
        while (amount-- > 0)
            next();
    }

    public boolean atQuestion() {
        return formDef.getChild(formEntryController.getModel().getFormIndex()) instanceof QuestionDef;
    }

    public String getQuestionRefAtIndex() {
        if (!atQuestion())
            throw new RuntimeException("Not at question");
        return ((TreeReference) getQuestionAtIndex().getBind().getReference()).toString(false, false);
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

    public Scenario onDagEvent(Consumer<Event> callback) {
        formDef.setEventNotifier(callback::accept);
        return this;
    }

    public Scenario onJump(Consumer<String> callback) {
        jumpCallback = callback;
        return this;
    }

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
}
