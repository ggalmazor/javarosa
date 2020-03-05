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

package org.javarosa.regression;

import static org.hamcrest.Matchers.is;
import static org.javarosa.core.test.AnswerDataMatchers.stringAnswer;
import static org.javarosa.core.util.BindBuilderXFormsElement.bind;
import static org.javarosa.core.util.XFormsElement.head;
import static org.javarosa.core.util.XFormsElement.html;
import static org.javarosa.core.util.XFormsElement.mainInstance;
import static org.javarosa.core.util.XFormsElement.model;
import static org.javarosa.core.util.XFormsElement.secondaryInstance;
import static org.javarosa.core.util.XFormsElement.t;
import static org.javarosa.core.util.XFormsElement.title;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import org.javarosa.core.test.Scenario;
import org.junit.Test;

public class Issue424Test {
@Test
public void name() throws IOException {
    Scenario scenario = Scenario.init("Some form", html(head(
        title("Some form"),
        model(
            mainInstance(t("data id=\"some-form\"",
                t("first-choice"),
                t("first-choice-randomized")
            )),
            secondaryInstance("choices",
                t("item", t("value", "a")),
                t("item", t("value", "b")),
                t("item", t("value", "c")),
                t("item", t("value", "d"))
            ),
            bind("/data/first-choice").type("string").calculate("instance('choices')/root/item[position() = 1]/value"),
            bind("/data/first-choice-randomized").type("string").calculate("randomize(instance('choices'), 42)/root/item[position() = 1]/value")
        )
    )));

    assertThat(scenario.answerOf("/data/first-choice"), is(stringAnswer("a")));
    assertThat(scenario.answerOf("/data/first-choice-randomized"), is(stringAnswer("b")));

}
}
