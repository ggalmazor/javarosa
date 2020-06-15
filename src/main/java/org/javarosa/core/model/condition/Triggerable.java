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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.javarosa.core.model.QuickTriggerable;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapList;
import org.javarosa.core.util.externalizable.ExtWrapTagged;
import org.javarosa.core.util.externalizable.Externalizable;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.debug.EvaluationResult;
import org.javarosa.xpath.XPathConditional;

/**
 * A triggerable represents an action that should be processed based
 * on a value updating in a model. Trigerrables are comprised of two
 * basic components: An expression to be evaluated, and a reference
 * which represents where the resultant value will be stored.
 * <p>
 * A triggerable will dispatch the action it's performing out to
 * all relevant nodes referenced by the context against thes current
 * models.
 *
 * @author ctsims
 */
public abstract class Triggerable implements Externalizable {
    /**
     * The expression which will be evaluated to produce a result
     */
    protected XPathConditional expr;

    /**
     * References to all of the (non-contextualized) nodes which should be
     * updated by the result of this triggerable
     */
    private Set<TreeReference> targets;

    /**
     * Current reference which is the "Basis" of the trigerrables being evaluated. This is the highest
     * common root of all of the targets being evaluated.
     */
    private TreeReference contextRef;  //generic ref used to turn triggers into absolute references

    // TODO Study why we really need this property. Looking at mutators, it should always equal the contextRef.
    /**
     * The first context provided to this triggerable before reducing to the common root.
     */
    private TreeReference originalContextRef;

    // TODO Move this into the DAG. This shouldn't be here.
    private Set<QuickTriggerable> immediateCascades = null;

    Triggerable() {

    }

    Triggerable(XPathConditional expr, TreeReference contextRef, TreeReference originalContextRef, Set<TreeReference> targets, Set<QuickTriggerable> immediateCascades) {
        this.expr = expr;
        this.targets = targets;
        this.contextRef = contextRef;
        this.originalContextRef = originalContextRef;
        this.immediateCascades = immediateCascades;
    }

    public static Triggerable condition(XPathConditional expr, ConditionAction trueAction, ConditionAction falseAction, TreeReference contextRef) {
        return new Condition(expr, contextRef, contextRef, new HashSet<>(), new HashSet<>(), trueAction, falseAction);
    }

    public static Triggerable recalculate(XPathConditional expr, TreeReference contextRef) {
        return new Recalculate(expr, contextRef, contextRef, new HashSet<>(), new HashSet<>());
    }

    public abstract Object eval(FormInstance instance, EvaluationContext ec);

    public abstract void apply(TreeReference ref, Object result, FormInstance mainInstance);

    public abstract boolean canCascade();

    public abstract boolean isCascadingToChildren();

    public Set<TreeReference> getTriggers() {
        return expr.getTriggers(originalContextRef);
    }

    public TreeReference contextualizeContextRef(TreeReference anchorRef) {
        // Contextualize the reference used by the triggerable against
        // the anchor
        return contextRef.contextualize(anchorRef);
    }

    public Set<TreeReference> getTargets() {
        return targets;
    }

    public void intersectContextWith(Triggerable other) {
        contextRef = contextRef.intersect(other.contextRef);
    }

    public TreeReference getContext() {
        return contextRef;
    }

    public TreeReference getOriginalContext() {
        return originalContextRef;
    }

    public void setImmediateCascades(Set<QuickTriggerable> cascades) {
        immediateCascades = new HashSet<>(cascades);
    }

    public Set<QuickTriggerable> getImmediateCascades() {
        return immediateCascades;
    }

    public IConditionExpr getExpr() {
        return expr;
    }

    public void addTarget(TreeReference target) {
        targets.add(target);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;

        if (!(o instanceof Triggerable))
            return false;

        Triggerable other = (Triggerable) o;

        // Both must have the same expression
        if (!expr.equals(other.expr))
            return false;

        if (!originalContextRef.equals(other.originalContextRef))
            return false;

        return true;
    }

    String buildHumanReadableTargetList() {
        StringBuilder targetsBuilder = new StringBuilder();
        for (TreeReference t : getTargets())
            targetsBuilder.append(t.toString(true, true)).append(", ");
        String targetsString = targetsBuilder.toString();
        return targetsString.isEmpty()
            ? "unknown refs (no targets added yet)"
            : targetsString.substring(0, targetsString.length() - 2);
    }

    // region External serialization

    @Override
    @SuppressWarnings("unchecked")
    public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
        expr = (XPathConditional) ExtUtil.read(in, new ExtWrapTagged(), pf);
        contextRef = (TreeReference) ExtUtil.read(in, TreeReference.class, pf);
        originalContextRef = (TreeReference) ExtUtil.read(in, TreeReference.class, pf);
        targets = new HashSet<>((List<TreeReference>) ExtUtil.read(in, new ExtWrapList(TreeReference.class), pf));
    }

    @Override
    public void writeExternal(DataOutputStream out) throws IOException {
        ExtUtil.write(out, new ExtWrapTagged(expr));
        ExtUtil.write(out, contextRef);
        ExtUtil.write(out, originalContextRef);
        ExtUtil.write(out, new ExtWrapList(new ArrayList<>(targets)));
    }

    // endregion
}
