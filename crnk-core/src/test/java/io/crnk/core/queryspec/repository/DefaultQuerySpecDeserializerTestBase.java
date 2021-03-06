package io.crnk.core.queryspec.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crnk.core.CoreTestContainer;
import io.crnk.core.CoreTestModule;
import io.crnk.core.engine.information.resource.ResourceInformation;
import io.crnk.core.engine.internal.utils.PropertyException;
import io.crnk.core.engine.parser.TypeParser;
import io.crnk.core.engine.registry.ResourceRegistry;
import io.crnk.core.exception.BadRequestException;
import io.crnk.core.exception.ParametersDeserializationException;
import io.crnk.core.mock.models.Project;
import io.crnk.core.mock.models.Task;
import io.crnk.core.mock.models.TaskWithLookup;
import io.crnk.core.module.SimpleModule;
import io.crnk.core.queryspec.AbstractQuerySpecTest;
import io.crnk.core.queryspec.DefaultQuerySpecDeserializer;
import io.crnk.core.queryspec.Direction;
import io.crnk.core.queryspec.FilterOperator;
import io.crnk.core.queryspec.FilterSpec;
import io.crnk.core.queryspec.QuerySpec;
import io.crnk.core.queryspec.QuerySpecDeserializerContext;
import io.crnk.core.queryspec.SortSpec;
import io.crnk.core.queryspec.pagingspec.CustomOffsetLimitPagingBehavior;
import io.crnk.core.queryspec.pagingspec.OffsetLimitPagingBehavior;
import io.crnk.core.queryspec.pagingspec.OffsetLimitPagingSpec;
import io.crnk.core.resource.RestrictedQueryParamsMembers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Deprecated
public abstract class DefaultQuerySpecDeserializerTestBase extends AbstractQuerySpecTest {

	@Rule
	public ExpectedException expectedException = ExpectedException.none();

	protected DefaultQuerySpecDeserializer deserializer;

	protected ResourceInformation taskInformation;

	private ResourceInformation taskWithPagingBehaviorInformation;

	private QuerySpecDeserializerContext deserializerContext;

	@Before
	public void setup() {
		super.setup();

		deserializerContext = new QuerySpecDeserializerContext() {

			@Override
			public ResourceRegistry getResourceRegistry() {
				return resourceRegistry;
			}

			@Override
			public TypeParser getTypeParser() {
				return moduleRegistry.getTypeParser();
			}

			@Override
			public ObjectMapper getObjectMapper() {
				return container.getObjectMapper();
			}
		};

		deserializer = new DefaultQuerySpecDeserializer();
		deserializer.init(deserializerContext);
		taskInformation = resourceRegistry.getEntry(Task.class).getResourceInformation();
		taskWithPagingBehaviorInformation = resourceRegistry.getEntry(TaskWithPagingBehavior.class).getResourceInformation();
	}

	@Override
	protected void setup(CoreTestContainer container) {
		container.addModule(new CoreTestModule());

		SimpleModule customPagingModule = new SimpleModule("customPaging");
		customPagingModule.addRepository(new TaskWithPagingBehaviorQuerySpecRepository());
		customPagingModule.addRepository(new TaskWithPagingBehaviorToProjectRelationshipRepository());
		customPagingModule.addPagingBehavior(new OffsetLimitPagingBehavior());
		customPagingModule.addPagingBehavior(new CustomOffsetLimitPagingBehavior());
		container.addModule(customPagingModule);
	}

	@Test
	public void operations() {
		Assert.assertFalse(deserializer.getAllowUnknownAttributes());
		deserializer.setAllowUnknownAttributes(true);
		Assert.assertTrue(deserializer.getAllowUnknownAttributes());
		deserializer.getSupportedOperators().clear();
		deserializer.setDefaultOperator(FilterOperator.LIKE);
		deserializer.addSupportedOperator(FilterOperator.LIKE);
		Assert.assertEquals(FilterOperator.LIKE, deserializer.getDefaultOperator());
		Assert.assertEquals(1, deserializer.getSupportedOperators().size());
	}

	@Test
	public void checkIgnoreParseExceptions() {
		Assert.assertFalse(deserializer.isIgnoreParseExceptions());
		deserializer.setIgnoreParseExceptions(true);
		Assert.assertTrue(deserializer.isIgnoreParseExceptions());

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[id]", "notAnInteger");
		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("id"), FilterOperator.EQ, "notAnInteger"));
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test(expected = ParametersDeserializationException.class)
	public void throwParseExceptionsByDefault() {
		Assert.assertFalse(deserializer.isIgnoreParseExceptions());

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[id]", "notAnInteger");
		deserializer.deserialize(taskInformation, params);
	}

	@Test(expected = ParametersDeserializationException.class)
	public void throwParseExceptionsForMultiValuedOffset() {
		Map<String, Set<String>> params = new HashMap<>();
		params.put("page[offset]", new HashSet<>(Arrays.asList("1", "2")));
		deserializer.deserialize(taskInformation, params);
	}

	@Test(expected = ParametersDeserializationException.class)
	public void throwParseExceptionsWhenBracketsNotClosed() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[", "someValue");
		deserializer.deserialize(taskInformation, params);
	}

	@Test
	public void testFindAll() {
		Map<String, Set<String>> params = new HashMap<>();

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void defaultPaginationOnRoot() {
		Map<String, Set<String>> params = new HashMap<>();
		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(0L, actualSpec.getOffset());
		Assert.assertNull(actualSpec.getLimit());
	}

	@Test
	public void defaultPaginationOnRelation() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "sort[projects]", "name");
		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(0L, actualSpec.getOffset());
		Assert.assertNull(actualSpec.getLimit());
		QuerySpec projectQuerySpec = actualSpec.getQuerySpec(Project.class);
		Assert.assertNotNull(projectQuerySpec);
		Assert.assertEquals(0L, projectQuerySpec.getOffset());
		Assert.assertNull(projectQuerySpec.getLimit());
	}

	@Test
	public void customPaginationOnRoot() {
		Map<String, Set<String>> params = new HashMap<>();
		QuerySpec actualSpec = deserializer.deserialize(taskWithPagingBehaviorInformation, params);
		Assert.assertEquals(1L, actualSpec.getOffset());
		Assert.assertEquals(10L, actualSpec.getLimit().longValue());
	}

	@Test
	public void customPaginationOnRelation() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "sort[projects]", "name");
		QuerySpec actualSpec = deserializer.deserialize(taskWithPagingBehaviorInformation, params);
		Assert.assertEquals(1L, actualSpec.getOffset());
		Assert.assertEquals(10L, actualSpec.getLimit().longValue());
		QuerySpec projectQuerySpec = actualSpec.getQuerySpec(Project.class);
		Assert.assertNotNull(projectQuerySpec);
		Assert.assertEquals(0L, projectQuerySpec.getOffset());
		Assert.assertNull(projectQuerySpec.getLimit());
	}

	@Test
	public void testFindAllOrderByAsc() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addSort(new SortSpec(Arrays.asList("name"), Direction.ASC));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "sort[tasks]", "name");
		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFollowNestedObjectWithinResource() {
		// follow ProjectData.data
		QuerySpec expectedSpec = new QuerySpec(Project.class);
		expectedSpec.addSort(new SortSpec(Arrays.asList("data", "data"), Direction.ASC));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "sort", "data.data");
		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testOrderByMultipleAttributes() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addSort(new SortSpec(Arrays.asList("name"), Direction.ASC));
		expectedSpec.addSort(new SortSpec(Arrays.asList("id"), Direction.ASC));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "sort[tasks]", "name,id");
		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFindAllOrderByDesc() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addSort(new SortSpec(Arrays.asList("name"), Direction.DESC));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "sort[tasks]", "-name");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFilterWithDefaultOp() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("name"), FilterOperator.EQ, "value"));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[tasks][name]", "value");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFilterWithComputedAttribute() {
		// if computeAttribte is not found, module order is wrong
		// tests setup with information builder must be hardened
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("computedAttribute"), FilterOperator.EQ, 13));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[tasks][computedAttribute]", "13");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFilterWithDotNotation() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("project", "name"), FilterOperator.EQ, "value"));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[project.name]", "value");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFilterWithDotNotationMultipleElements() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("project", "task", "name"), FilterOperator.EQ, "value"));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[project.task.name]", "value");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testUnknownPropertyAllowed() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("doesNotExists"), FilterOperator.EQ, "value"));

		deserializer.setAllowUnknownAttributes(true);

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[tasks][doesNotExists]", "value");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test(expected = PropertyException.class)
	public void testUnknownPropertyNotAllowed() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("doesNotExists"), FilterOperator.EQ, "value"));

		deserializer.setAllowUnknownAttributes(false);

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[tasks][doesNotExists]", "value");

		deserializer.deserialize(taskInformation, params);
	}

	@Test
	public void testUnknownParameterAllowed() {
		deserializer.setAllowUnknownParameters(true);

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "doesNotExists[tasks]", "value");

		Assert.assertNotNull(deserializer.deserialize(taskInformation, params));
	}

	@Test(expected = ParametersDeserializationException.class)
	public void testUnknownParameterNotAllowed() {
		deserializer.setAllowUnknownParameters(false);

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "doesNotExists[tasks]", "value");

		deserializer.deserialize(taskInformation, params);
	}

	@Test
	public void testFilterByOne() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("name"), FilterOperator.EQ, "value"));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[tasks][name][EQ]", "value");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFilterByMany() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(
				new FilterSpec(Arrays.asList("name"), FilterOperator.EQ, new HashSet<>(Arrays.asList("value1", "value2"))));

		Map<String, Set<String>> params = new HashMap<>();
		params.put("filter[tasks][name][EQ]", new HashSet<>(Arrays.asList("value1", "value2")));

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFilterEquals() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("id"), FilterOperator.EQ, 1L));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[tasks][id][EQ]", "1");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFilterGreater() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("id"), FilterOperator.LE, 1L));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[tasks][id][LE]", "1");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testFilterGreaterOnRoot() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addFilter(new FilterSpec(Arrays.asList("id"), FilterOperator.LE, 1L));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[id][LE]", "1");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testPaging() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.setPagingSpec(new OffsetLimitPagingSpec(1L, 2L));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "page[offset]", "1");
		add(params, "page[limit]", "2");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void deserializeUnknownParameter() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "doesNotExist[something]", "someValue");

		final boolean[] deserialized = new boolean[1];
		deserializer = new DefaultQuerySpecDeserializer() {

			@Override
			protected void deserializeUnknown(final QuerySpec querySpec, final Parameter parameter) {
				Assert.assertEquals(RestrictedQueryParamsMembers.unknown, parameter.getParamType());
				Assert.assertEquals("doesNotExist", parameter.getStrParamType());
				Assert.assertEquals("doesNotExist[something]", parameter.getName());
				Assert.assertNull(parameter.getResourceInformation());
				Assert.assertEquals(FilterOperator.EQ, parameter.getOperator());
				Assert.assertNull(parameter.getAttributePath());
				Assert.assertEquals(1, parameter.getValues().size());
				deserialized[0] = true;
			}
		};
		deserializer.init(deserializerContext);
		deserializer.deserialize(taskInformation, params);
		Assert.assertTrue(deserialized[0]);
	}


	@Test(expected = ParametersDeserializationException.class)
	public void testInvalidPagingType() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "page[doesNotExist]", "1");
		deserializer.deserialize(taskInformation, params);
	}

	@Test(expected = ParametersDeserializationException.class)
	public void testPagingError() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.setLimit(2L);
		expectedSpec.setOffset(1L);

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "page[offset]", "notANumber");
		add(params, "page[limit]", "2");

		deserializer.deserialize(taskInformation, params);
	}

	@Test
	public void testPagingMaxLimitNotAllowed() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "page[offset]", "1");
		add(params, "page[limit]", "30");

		expectedException.expect(BadRequestException.class);

		deserializer.deserialize(taskWithPagingBehaviorInformation, params);
	}

	@Test
	public void testPagingMaxLimitAllowed() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.setOffset(1L);
		expectedSpec.setLimit(5L);

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "page[offset]", "1");
		add(params, "page[limit]", "5");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testIncludeRelations() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.includeRelation(Arrays.asList("project"));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "include[tasks]", "project");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testIncludeRelationsOnRoot() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.includeRelation(Arrays.asList("project"));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "include", "project");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testIncludeAttributes() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.includeField(Arrays.asList("name"));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "fields[tasks]", "name");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testIncludeAttributesOnRoot() {
		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.includeField(Arrays.asList("name"));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "fields", "name");

		QuerySpec actualSpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testHyphenIsAllowedInResourceName() {

		QuerySpec expectedSpec = new QuerySpec(Task.class);
		expectedSpec.addSort(new SortSpec(Arrays.asList("id"), Direction.ASC));

		Map<String, Set<String>> params = new HashMap<>();
		add(params, "sort[task-with-lookup]", "id");

		ResourceInformation taskWithLookUpInformation =
				resourceRegistry.getEntryForClass(TaskWithLookup.class).getResourceInformation();
		QuerySpec actualSpec = deserializer.deserialize(taskWithLookUpInformation, params);
		Assert.assertEquals(expectedSpec, actualSpec);
	}

	@Test
	public void testIngoreParseException() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[id]", "NotAnId");
		deserializer.setIgnoreParseExceptions(true);
		QuerySpec querySpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(Task.class, querySpec.getResourceClass());
		Assert.assertEquals(Arrays.asList("id"), querySpec.getFilters().get(0).getAttributePath());
		Assert.assertEquals("NotAnId", querySpec.getFilters().get(0).getValue());
		Assert.assertNull(querySpec.getRelatedSpecs().get(Project.class));
	}

	@Test
	public void testGenericCast() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[id]", "12");
		add(params, "filter[name]", "test");
		add(params, "filter[completed]", "true");
		deserializer.setIgnoreParseExceptions(false);
		QuerySpec querySpec = deserializer.deserialize(taskInformation, params);
		Assert.assertEquals(Task.class, querySpec.getResourceClass());
		Assert.assertEquals(Arrays.asList("id"), querySpec.getFilters().get(2).getAttributePath());
		Long id = querySpec.getFilters().get(2).getValue();
		Assert.assertEquals(Long.valueOf(12), id);
		String name = querySpec.getFilters().get(0).getValue();
		Assert.assertEquals("test", name);
		Boolean completed = querySpec.getFilters().get(1).getValue();
		Assert.assertEquals(Boolean.TRUE, completed);
		Assert.assertNull(querySpec.getRelatedSpecs().get(Project.class));
	}

	@Test(expected = ParametersDeserializationException.class)
	public void testFailOnParseException() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "filter[id]", "NotAnId");
		deserializer.setIgnoreParseExceptions(false);
		deserializer.deserialize(taskInformation, params);
	}

	@Test(expected = ParametersDeserializationException.class)
	public void testUnknownProperty() {
		Map<String, Set<String>> params = new HashMap<>();
		add(params, "group", "test");
		deserializer.setIgnoreParseExceptions(false);
		deserializer.deserialize(taskInformation, params);
	}

	protected void add(Map<String, Set<String>> params, String key, String value) {
		params.put(key, new HashSet<>(Arrays.asList(value)));
	}
}
