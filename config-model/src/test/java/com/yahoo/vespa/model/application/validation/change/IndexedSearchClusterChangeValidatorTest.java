// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation.change;

import com.yahoo.config.model.api.ConfigChangeAction;
import com.yahoo.config.model.api.ServiceInfo;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.application.validation.ValidationOverrides;
import com.yahoo.vespa.model.application.validation.change.IndexedSearchClusterChangeValidator;
import com.yahoo.vespa.model.application.validation.change.VespaConfigChangeAction;
import com.yahoo.vespa.model.application.validation.change.VespaRefeedAction;
import com.yahoo.vespa.model.content.utils.ApplicationPackageBuilder;
import com.yahoo.vespa.model.content.utils.ContentClusterBuilder;
import com.yahoo.vespa.model.content.utils.SearchDefinitionBuilder;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static com.yahoo.vespa.model.application.validation.change.ConfigChangeTestUtils.newRestartAction;
import static com.yahoo.vespa.model.application.validation.change.ConfigChangeTestUtils.newRefeedAction;

public class IndexedSearchClusterChangeValidatorTest {

    static class Fixture {
        VespaModel currentModel;
        VespaModel nextModel;
        IndexedSearchClusterChangeValidator validator;

        public Fixture(VespaModel currentModel, VespaModel nextModel) {
            this.currentModel = currentModel;
            this.nextModel = nextModel;
            validator = new IndexedSearchClusterChangeValidator();
        }

        public static Fixture newOneDocFixture(String currentSd, String nextSd) {
            return new Fixture(newOneDocModel(currentSd), newOneDocModel(nextSd));
        }

        public static VespaModel newOneDocModel(String sdContent) {
            return new ApplicationPackageBuilder().
                    addCluster(new ContentClusterBuilder().name("foo").docTypes("d1")).
                    addSearchDefinition(new SearchDefinitionBuilder().
                            name("d1").content(sdContent).build()).buildCreator().create();
        }

        public static Fixture newTwoDocFixture(String currentSd, String nextSd) {
            return new Fixture(newTwoDocModel(currentSd, currentSd), newTwoDocModel(nextSd, nextSd));
        }

        public static VespaModel newTwoDocModel(String d1Content, String d2Content) {
            return new ApplicationPackageBuilder().
                    addCluster(new ContentClusterBuilder().name("foo").docTypes("d1", "d2")).
                    addSearchDefinition(new SearchDefinitionBuilder().
                            name("d1").content(d1Content).build()).
                    addSearchDefinition(new SearchDefinitionBuilder().
                            name("d2").content(d2Content).build()).
                    buildCreator().create();
        }

        public static Fixture newTwoClusterFixture(String currentSd, String nextSd) {
            return new Fixture(newTwoClusterModel(currentSd, currentSd), newTwoClusterModel(nextSd, nextSd));
        }

        public static VespaModel newTwoClusterModel(String d1Content, String d2Content) {
            return new ApplicationPackageBuilder().
                    addCluster(new ContentClusterBuilder().name("foo").docTypes("d1")).
                    addCluster(new ContentClusterBuilder().name("bar").docTypes("d2")).
                    addSearchDefinition(new SearchDefinitionBuilder().
                            name("d1").content(d1Content).build()).
                    addSearchDefinition(new SearchDefinitionBuilder().
                            name("d2").content(d2Content).build()).
                    buildCreator().create();
        }

        public void assertValidation() {
            List<ConfigChangeAction> act = normalizeServicesInActions(validator.validate(currentModel, nextModel,
                                                                                         ValidationOverrides.empty()));
            assertThat(act.size(), is(0));
        }

        private static List<ConfigChangeAction> normalizeServicesInActions(List<ConfigChangeAction> result) {
            return result.stream().
                    map(action -> ((VespaConfigChangeAction) action).modifyAction(
                            action.getMessage(),
                            normalizeServices(action.getServices()),
                            action.getType().equals(ConfigChangeAction.Type.REFEED) ?
                            ((VespaRefeedAction)action).getDocumentType() : "")).
                    collect(Collectors.toList());
        }

        private static List<ServiceInfo> normalizeServices(List<ServiceInfo> services) {
            return services.stream().
                    map(service -> new ServiceInfo(service.getServiceName(), "null", null, null,
                            service.getConfigId(), "null")).
                    collect(Collectors.toList());
        }

        public void assertValidation(ConfigChangeAction exp) {
            assertValidation(Arrays.asList(exp));
        }

        public void assertValidation(List<ConfigChangeAction> exp) {
            List<ConfigChangeAction> act = normalizeServicesInActions(validator.validate(currentModel, nextModel,
                                                                                         ValidationOverrides.empty()));
            exp.sort((lhs, rhs) -> lhs.getMessage().compareTo(rhs.getMessage()));
            act.sort((lhs, rhs) -> lhs.getMessage().compareTo(rhs.getMessage()));
            assertThat(act, equalTo(exp));
        }
    }

    static String STRING_FIELD = "field f1 type string { indexing: summary }";
    static String ATTRIBUTE_FIELD = "field f1 type string { indexing: attribute | summary }";
    static String ATTRIBUTE_CHANGE_MSG = "Field 'f1' changed: add attribute aspect";
    static String INT_FIELD = "field f1 type int { indexing: summary }";
    static String FIELD_TYPE_CHANGE_MSG = "Field 'f1' changed: data type: 'string' -> 'int'";
    private static List<ServiceInfo> FOO_SERVICE = Arrays.asList(
            new ServiceInfo("searchnode", "null", null, null, "foo/search/cluster.foo/0", "null"));
    private static List<ServiceInfo> BAR_SERVICE = Arrays.asList(
            new ServiceInfo("searchnode2", "null", null, null, "bar/search/cluster.bar/0", "null"));

    @Test
    public void requireThatDocumentDatabaseChangeIsDiscovered() {
        Fixture.newOneDocFixture(STRING_FIELD, ATTRIBUTE_FIELD).
                assertValidation(newRestartAction("Document type 'd1': " + ATTRIBUTE_CHANGE_MSG, FOO_SERVICE));
    }

    @Test
    public void requireThatChangeInSeveralDocumentDatabasesAreDiscovered() {
        Fixture.newTwoDocFixture(STRING_FIELD, ATTRIBUTE_FIELD).
                assertValidation(Arrays.asList(newRestartAction("Document type 'd1': "
                                        + ATTRIBUTE_CHANGE_MSG, FOO_SERVICE),
                        newRestartAction("Document type 'd2': " + ATTRIBUTE_CHANGE_MSG, FOO_SERVICE)));
    }

    @Test
    public void requireThatChangeInSeveralContentClustersAreDiscovered() {
        Fixture.newTwoClusterFixture(STRING_FIELD, ATTRIBUTE_FIELD).
                assertValidation(Arrays.asList(newRestartAction("Document type 'd1': "
                                + ATTRIBUTE_CHANGE_MSG, FOO_SERVICE),
                        newRestartAction("Document type 'd2': " + ATTRIBUTE_CHANGE_MSG, BAR_SERVICE)));
    }

    @Test
    public void requireThatAddingDocumentDatabaseIsOk() {
        new Fixture(Fixture.newOneDocModel(STRING_FIELD), Fixture.newTwoDocModel(STRING_FIELD, STRING_FIELD)).assertValidation();
    }

    @Test
    public void requireThatRemovingDocumentDatabaseIsOk() {
        new Fixture(Fixture.newTwoDocModel(STRING_FIELD, STRING_FIELD), Fixture.newOneDocModel(STRING_FIELD)).assertValidation();
    }

    @Test
    public void requireThatAddingContentClusterIsOk() {
        new Fixture(Fixture.newOneDocModel(STRING_FIELD), Fixture.newTwoClusterModel(STRING_FIELD, STRING_FIELD)).assertValidation();
    }

    @Test
    public void requireThatRemovingContentClusterIsOk() {
        new Fixture(Fixture.newTwoClusterModel(STRING_FIELD, STRING_FIELD), Fixture.newOneDocModel(STRING_FIELD)).assertValidation();
    }

    @Test
    public void requireThatChangingFieldTypeIsDiscovered() {
        Fixture f = Fixture.newOneDocFixture(STRING_FIELD, INT_FIELD);
        f.assertValidation(Arrays.asList(newRefeedAction("field-type-change",
                                                        ValidationOverrides.empty(),
                                                         "Document type 'd1': " + FIELD_TYPE_CHANGE_MSG, FOO_SERVICE, "d1")));
    }

}
