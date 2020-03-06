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
import static org.javarosa.test.utils.ResourcePathHelper.r;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.javarosa.core.reference.ReferenceManagerTestUtils;
import org.javarosa.core.test.Scenario;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.junit.Test;

public class Issue449Test {

    @Test
    public void support_for_same_references_to_different_instances_without_DAG_cycles() throws IOException, DeserializationException {
        Path formFile = r("issue_449.xml");
        ReferenceManagerTestUtils.setUpSimpleReferenceManager(formFile.getParent(), "file");
        Scenario scenario = Scenario.init(formFile);

        scenario.answer("/data/new-part", "c");
        assertThat(scenario.answerOf("/data/aggregated"), is(stringAnswer("a b c")));

        Scenario deserialized = scenario.serializeAndDeserializeForm();
        assertThat(deserialized.answerOf("/data/new-part"), is(stringAnswer("c")));
        assertThat(deserialized.answerOf("/data/aggregated"), is(stringAnswer("a b c")));

        deserialized.answer("/data/new-part", "c2");
        assertThat(deserialized.answerOf("/data/aggregated"), is(stringAnswer("a b c2")));
    }
}
