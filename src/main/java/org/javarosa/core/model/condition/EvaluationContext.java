/*
 * Copyright (C) 2009 JavaRosa
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.javarosa.core.model.condition;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.xpath.IExprDataType;
import org.javarosa.xpath.XPathNodeset;
import org.javarosa.xpath.expr.XPathEqExpr;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.expr.XPathPathExpr;

/**
 * A collection of objects that affect the evaluation of an expression, like
 * function handlers and (not supported) variable bindings.
 */
public class EvaluationContext {
    /**
     * Unambiguous anchor reference for relative paths
     */
    private TreeReference contextNode;
    private HashMap<String, IFunctionHandler> functionHandlers;
    private HashMap<String, Object> variables;

    public boolean isConstraint; //true if we are evaluating a constraint
    public IAnswerData candidateValue; //if isConstraint, this is the value being validated
    public boolean isCheckAddChild; //if isConstraint, true if we are checking the constraint of a parent node on how
    //  many children it may have

    private String outputTextForm = null; //Responsible for informing itext what form is requested if relevant

    private HashMap<String, DataInstance> formInstances;

    private TreeReference original;
    /**
     * What element in a nodeset the context is currently pointing to.
     * Used for calculating the position() xpath function.
     */
    private int currentContextPosition = -1;

    private DataInstance instance;

    /**
     * Copy Constructor
     **/
    private EvaluationContext(EvaluationContext base) {
        //TODO: These should be deep, not shallow
        functionHandlers = base.functionHandlers;
        formInstances = base.formInstances;
        variables = base.variables;

        contextNode = base.contextNode;
        instance = base.instance;

        isConstraint = base.isConstraint;
        candidateValue = base.candidateValue;
        isCheckAddChild = base.isCheckAddChild;

        outputTextForm = base.outputTextForm;
        original = base.original;

        //Hrm....... not sure about this one. this only happens after a rescoping,
        //and is fixed on the context. Anything that changes the context should
        //invalidate this
        currentContextPosition = base.currentContextPosition;
    }

    public EvaluationContext(EvaluationContext base, TreeReference context) {
        this(base);
        this.contextNode = context;
    }

    public EvaluationContext(EvaluationContext base, HashMap<String, DataInstance> formInstances, TreeReference context) {
        this(base, context);
        this.formInstances = formInstances;
    }

    public EvaluationContext(DataInstance instance, HashMap<String, DataInstance> formInstances, EvaluationContext base) {
        this(base);
        this.formInstances = formInstances;
        this.instance = instance;
    }

    public EvaluationContext(DataInstance instance) {
        this(instance, new HashMap<>());
    }

    public EvaluationContext(DataInstance instance, HashMap<String, DataInstance> formInstances) {
        this.formInstances = formInstances;
        this.instance = instance;
        this.contextNode = TreeReference.rootRef();
        functionHandlers = new HashMap<>();
        variables = new HashMap<>();
    }

    public DataInstance getInstance(String id) {
        DataInstance formInstance = formInstances.get(id);
        return formInstance != null ? formInstance :
            (instance != null && id.equals(instance.getName()) ? instance : null);
    }

    public TreeReference getContextRef() {
        return contextNode;
    }

    public void setOriginalContext(TreeReference ref) {
        original = ref;
    }

    public TreeReference getOriginalContext() {
        return (original == null) ? contextNode : original;
    }

    public void addFunctionHandler(IFunctionHandler fh) {
        functionHandlers.put(fh.getName(), fh);
    }

    public HashMap<String, IFunctionHandler> getFunctionHandlers() {
        return functionHandlers;
    }

    public void setOutputTextForm(String form) {
        outputTextForm = form;
    }

    public String getOutputTextForm() {
        return outputTextForm;
    }

    public void setVariables(HashMap<String, ?> variables) {
        for (String var : variables.keySet()) {
            setVariable(var, variables.get(var));
        }
    }

    public void setVariable(String name, Object value) {
        //No such thing as a null xpath variable. Empty
        //values in XPath just get converted to ""
        if (value == null) {
            variables.put(name, "");
            return;
        }
        //Otherwise check whether the value is one of the normal first
        //order datatypes used in xpath evaluation
        if (value instanceof Boolean ||
            value instanceof Double ||
            value instanceof String ||
            value instanceof Date ||
            value instanceof IExprDataType) {
            variables.put(name, value);
            return;
        }

        //Some datatypes can be trivially converted to a first order
        //xpath datatype
        if (value instanceof Integer) {
            variables.put(name, ((Integer) value).doubleValue());
        } else if (value instanceof Float) {
            variables.put(name, ((Float) value).doubleValue());
        } else { //Otherwise we just hope for the best, I suppose? Should we log this?
            variables.put(name, value);
        }
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public List<TreeReference> expandReference(TreeReference ref) {
        return expandReference(ref, false);
    }

    /**
     * Searches for all repeated nodes that match the pattern of the 'ref'
     * argument.
     * <p>
     * '/' returns {'/'}
     * can handle sub-repetitions (e.g., {/a[1]/b[1], /a[1]/b[2], /a[2]/b[1]})
     *
     * @param ref Potentially ambiguous reference
     * @return Null if 'ref' is relative reference. Otherwise, returns a vector
     *     of references that point to nodes that match 'ref' argument. These
     *     references are unambiguous (no index will ever be INDEX_UNBOUND). Template
     *     nodes won't be included when matching INDEX_UNBOUND, but will be when
     *     INDEX_TEMPLATE is explicitly set.
     */
    public List<TreeReference> expandReference(TreeReference ref, boolean includeTemplates) {
        if (!ref.isAbsolute()) {
            return null;
        }

        final DataInstance baseInstance = (ref.getInstanceName() != null) ? getInstance(ref.getInstanceName()) : instance;

        if (baseInstance == null) {
            throw new RuntimeException("Unable to expand reference " + ref.toString(true) +
                ", no appropriate instance in evaluation context");
        }

        List<TreeReference> treeReferences = new ArrayList<>(1);
        TreeReference workingRef = baseInstance.getRoot().getRef();
        expandReferenceAccumulator(ref, baseInstance, workingRef, treeReferences, includeTemplates);
        return treeReferences;
    }

    /**
     * Recursively performs the search for all repeated nodes that match the pattern of the 'ref' argument.
     *
     * @param sourceRef      original path we're matching against
     * @param sourceInstance original node obtained from sourceRef
     * @param workingRef     explicit path that refers to the current node
     * @param refs           accumulator List to collect matching paths.
     */
    @SuppressWarnings("unchecked")
    private void expandReferenceAccumulator(TreeReference sourceRef, DataInstance sourceInstance,
                                            TreeReference workingRef, List<TreeReference> refs,
                                            boolean includeTemplates) {
        int depth = workingRef.size();

        if (depth == sourceRef.size()) {
            //TODO: Do we need to clone these references?
            refs.add(workingRef);
            return;
        }

        AbstractTreeElement<TreeElement> node = (AbstractTreeElement<TreeElement>) sourceInstance.resolveReference(workingRef);

        List<XPathExpression> sourcePredicates = sourceRef.getPredicate(depth);
        List<XPathExpression> copyPredicates = sourcePredicates != null ? new ArrayList<>(sourcePredicates) : new ArrayList<>();

        if (canUseOptimizedAlgorithm(sourceRef, workingRef, node, copyPredicates))
            optimizedEqExprPredicateAlgorithm(sourceRef, sourceInstance, refs, includeTemplates, node, (XPathEqExpr) copyPredicates.get(0), depth);
        else
            originalAlgorithm(sourceRef, sourceInstance, refs, includeTemplates, node, copyPredicates, depth);
    }

    private boolean canUseOptimizedAlgorithm(TreeReference sourceRef, TreeReference workingRef, AbstractTreeElement<TreeElement> node, List<XPathExpression> predicates) {
        return node.getNumChildren() > 0 &&  // The node has children
            sourceRef.getMultiplicity(workingRef.size()) == TreeReference.INDEX_UNBOUND && // The form asks for an unbounded amount of nodes
            predicates.size() == 1 && // The form defines exactly one predicate
            predicates.get(0) instanceof XPathEqExpr; // And the predicate is composed by an equals expression
    }

    private void optimizedEqExprPredicateAlgorithm(TreeReference sourceRef, DataInstance sourceInstance, List<TreeReference> refs, boolean includeTemplates, AbstractTreeElement<TreeElement> node, XPathEqExpr predicate, int depth) {
        String filterFieldName = resolveFilterFieldName(predicate);
        String filterValue = resolveFilterValue(predicate, sourceInstance);

        if (filterFieldName == null || filterValue == null) {
            // Something has gone wrong while trying to extract filter params and the
            // optimized algorithm can't continue. Revert to the original algorithm
            originalAlgorithm(sourceRef, sourceInstance, refs, includeTemplates, node, singletonList(predicate), depth);
            return;
        }

        String nodeName = sourceRef.getName(depth);
        List<TreeReference> treeReferences = new ArrayList<>();
        for (TreeElement child : node.getChildrenWithName(nodeName, filterFieldName, filterValue))
            treeReferences.add(child.getRef());

        if (includeTemplates) {
            TreeReference templateRef = getChildRefByNameAndMult(nodeName, TreeReference.INDEX_TEMPLATE, node);
            if (templateRef != null)
                treeReferences.add(templateRef);
        }

        for (TreeReference treeRef : treeReferences)
            expandReferenceAccumulator(sourceRef, sourceInstance, treeRef, refs, includeTemplates);
    }

    private String resolveFilterFieldName(XPathEqExpr predicate) {
        String fieldNameInA = extractEqExprFieldName(predicate.a);
        return fieldNameInA != null
            ? fieldNameInA
            : extractEqExprFieldName(predicate.b);

    }

    private XPathPathExpr resolveXPathPathExpr(XPathEqExpr predicate) {
        if (extractEqExprFieldName(predicate.a) != null)
            return (XPathPathExpr) predicate.b;

        if (extractEqExprFieldName(predicate.b) != null)
            return (XPathPathExpr) predicate.a;

        return null;
    }

    private String resolveFilterValue(XPathEqExpr predicate, DataInstance sourceInstance) {
        XPathPathExpr valueExpr = resolveXPathPathExpr(predicate);
        if (valueExpr == null)
            return null;

        XPathNodeset eval = valueExpr.eval(sourceInstance, this);
        return (String) eval.getValAt(0);
    }

    private TreeReference getChildRefByNameAndMult(String name, int mult, AbstractTreeElement<TreeElement> node) {
        TreeElement child = node.getChild(name, mult);
        return child != null ? child.getRef() : null;
    }

    private TreeReference getAttributeByName(String name, AbstractTreeElement<TreeElement> node) {
        TreeElement attribute = node.getAttribute(null, name);
        return attribute != null ? attribute.getRef() : null;
    }

    private void originalAlgorithm(TreeReference sourceRef, DataInstance sourceInstance, List<TreeReference> refs, boolean includeTemplates, AbstractTreeElement<TreeElement> node, List<XPathExpression> predicates, int depth) {
        List<TreeReference> treeReferences = getRefs(includeTemplates, node, sourceRef.getName(depth), sourceRef.getMultiplicity(depth));

        List<TreeReference> filteredRefs = filterRefs(sourceInstance, predicates, treeReferences);

        for (TreeReference treeRef : filteredRefs) {
            expandReferenceAccumulator(sourceRef, sourceInstance, treeRef, refs, includeTemplates);
        }
    }

    private List<TreeReference> filterRefs(DataInstance sourceInstance, List<XPathExpression> predicates, List<TreeReference> treeReferences) {
        if (predicates.isEmpty())
            return treeReferences;

        List<TreeReference> filteredRefs = new ArrayList<>();
        for (TreeReference ref : treeReferences) {
            boolean passes = true;
            Iterator<XPathExpression> predicateIterator = predicates.iterator();
            while (passes && predicateIterator.hasNext()) {
                // TODO This sentence will throw if the predicate produces a value that's not casteable to Boolean. Throw a controlled exception with a useful message instead.
                passes = (Boolean) predicateIterator.next().eval(sourceInstance, this);
            }
            if (passes)
                filteredRefs.add(ref);
        }
        return filteredRefs;
    }

    private List<TreeReference> getRefs(boolean includeTemplates, AbstractTreeElement<TreeElement> node, String name, int mult) {
        if (node.getNumChildren() > 0 && mult == TreeReference.INDEX_UNBOUND) {
            int i = 0;
            final List<TreeReference> treeReferences = new ArrayList<>(1);
            for (TreeElement child : node.getChildrenWithName(name)) {
                if (child.getMultiplicity() != i++)
                    throw new IllegalStateException("Unexpected multiplicity mismatch");
                treeReferences.add(child.getRef());
            }
            if (includeTemplates) {
                TreeReference templateRef = getChildRefByNameAndMult(name, TreeReference.INDEX_TEMPLATE, node);
                if (templateRef != null)
                    treeReferences.add(templateRef);
            }
            return treeReferences;
        }
        if (node.getNumChildren() > 0 && mult != TreeReference.INDEX_ATTRIBUTE) {
            //TODO: Make this test mult >= 0?
            //If the multiplicity is a simple integer, just get
            //the appropriate child
            TreeReference childRef = getChildRefByNameAndMult(name, mult, node);
            if (childRef != null)
                return singletonList(childRef);
        }

        if (mult == TreeReference.INDEX_ATTRIBUTE) {
            TreeReference attributeRef = getAttributeByName(name, node);
            if (attributeRef != null)
                return singletonList(attributeRef);
        }
        return new ArrayList<>();
    }

    private String extractEqExprFieldName(XPathExpression eqExprPart) {
        if (eqExprPart instanceof XPathPathExpr) {
            XPathPathExpr xppe = (XPathPathExpr) eqExprPart;
            if (!xppe.getReference().isAbsolute() && xppe.steps.length == 1)
                return xppe.steps[0].name.name;
        }
        return null;
    }

    public DataInstance getMainInstance() {
        return instance;
    }

    public AbstractTreeElement resolveReference(TreeReference qualifiedRef) {
        DataInstance instance = getMainInstance();
        if (qualifiedRef.getInstanceName() != null) {
            instance = getInstance(qualifiedRef.getInstanceName());
        }
        return instance.resolveReference(qualifiedRef);
    }

    public int getContextPosition() {
        return currentContextPosition;
    }
}
