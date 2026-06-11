package idea.bear.sunday.input;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtractInputDtoTextRefactoringTest {
    private final ExtractInputDtoTextRefactoring refactoring = new ExtractInputDtoTextRefactoring();

    @Test
    void extractsSelectedParametersToInputDto() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            use BEAR\\Resource\\ResourceObject;

            final class Plot extends ResourceObject
            {
                /**
                 * @param int $x X coordinate
                 * @param int $y Y coordinate
                 * @param string $label Label
                 */
                public function onGet(int $x, int $y, string $label = 'origin'): static
                {
                    $this->body = ['x' => $x, 'y' => $y, 'label' => $label];

                    return $this;
                }
            }
            """;

        int offset = source.indexOf("onGet");
        ExtractInputDtoTextRefactoring.RefactoringResult result = refactoring.refactorResource(
            source,
            offset,
            Set.of("x", "y"),
            "Point",
            "p",
            "MyVendor\\Todo\\Input\\Point"
        );

        assertTrue(result.resourceText().contains("use Ray\\InputQuery\\Attribute\\Input;"));
        assertTrue(result.resourceText().contains("use MyVendor\\Todo\\Input\\Point;"));
        assertTrue(result.resourceText().contains("public function onGet(#[Input] Point $p, string $label = 'origin'): static"));
        assertTrue(result.resourceText().contains("'x' => $p->x"));
        assertTrue(result.resourceText().contains("'y' => $p->y"));
        assertTrue(result.resourceText().contains("@param string $label Label"));
        assertFalse(result.resourceText().contains("@param int $x X coordinate"));
        assertFalse(result.resourceText().contains("@param int $y Y coordinate"));

        assertTrue(result.dtoText().contains("namespace MyVendor\\Todo\\Input;"));
        assertTrue(result.dtoText().contains("final class Point"));
        assertTrue(result.dtoText().contains("@param int $x X coordinate"));
        assertTrue(result.dtoText().contains("@param int $y Y coordinate"));
        assertTrue(result.dtoText().contains("#[Input] public readonly int $x,"));
        assertTrue(result.dtoText().contains("#[Input] public readonly int $y"));
    }

    @Test
    void derivesDefaultInputNamespaceFromResourceNamespace() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\Page\\User;
            """;

        assertEquals("MyVendor\\Todo\\Input", refactoring.defaultInputNamespace(source));
    }

    @Test
    void rejectsUnsupportedParameter() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            final class Plot
            {
                public function onGet(string & $x): static
                {
                    return $this;
                }
            }
            """;

        assertThrows(IllegalArgumentException.class, () -> refactoring.refactorResource(
            source,
            source.indexOf("onGet"),
            Set.of("x"),
            "Point",
            "p",
            "MyVendor\\Todo\\Input\\Point"
        ));
    }

    @Test
    void doesNotReplaceVariablesInsideStringsOrComments() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            final class Plot
            {
                public function onGet(int $x): static
                {
                    $literal = '$x should stay';
                    $template = "also $x should stay";
                    // $x should stay in line comment
                    /* $x should stay in block comment */
                    $this->body = ['x' => $x];

                    return $this;
                }
            }
            """;

        ExtractInputDtoTextRefactoring.RefactoringResult result = refactoring.refactorResource(
            source,
            source.indexOf("onGet"),
            Set.of("x"),
            "Point",
            "p",
            "MyVendor\\Todo\\Input\\Point"
        );

        assertTrue(result.resourceText().contains("$literal = '$x should stay';"));
        assertTrue(result.resourceText().contains("$template = \"also $x should stay\";"));
        assertTrue(result.resourceText().contains("// $x should stay in line comment"));
        assertTrue(result.resourceText().contains("/* $x should stay in block comment */"));
        assertTrue(result.resourceText().contains("'x' => $p->x"));
    }

    @Test
    void rejectsModifiedSelectedParameter() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            final class Plot
            {
                public function onGet(int $x): static
                {
                    $x++;

                    return $this;
                }
            }
            """;

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> refactoring.refactorResource(
            source,
            source.indexOf("onGet"),
            Set.of("x"),
            "Point",
            "p",
            "MyVendor\\Todo\\Input\\Point"
        ));
        assertTrue(exception.getMessage().contains("modified"));
    }

    @Test
    void ignoresBracesInsideStringsAndCommentsWhenFindingMethod() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            final class Plot
            {
                public function onGet(int $x): static
                {
                    $literal = '}';
                    /* } */
                    $this->body = ['x' => $x];

                    return $this;
                }
            }
            """;

        ExtractInputDtoTextRefactoring.RefactoringResult result = refactoring.refactorResource(
            source,
            source.indexOf("$this->body"),
            Set.of("x"),
            "Point",
            "p",
            "MyVendor\\Todo\\Input\\Point"
        );

        assertTrue(result.resourceText().contains("'x' => $p->x"));
    }

    @Test
    void allowsReadingSelectedParameterInComparison() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            final class Plot
            {
                public function onGet(int $x): static
                {
                    $this->body = ['positive' => $x === 1];

                    return $this;
                }
            }
            """;

        ExtractInputDtoTextRefactoring.RefactoringResult result = refactoring.refactorResource(
            source,
            source.indexOf("onGet"),
            Set.of("x"),
            "Point",
            "p",
            "MyVendor\\Todo\\Input\\Point"
        );

        assertTrue(result.resourceText().contains("$p->x === 1"));
    }

    @Test
    void removesEmptyDocCommentWhenAllParamDocsMoveToDto() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            final class Plot
            {
                /**
                 * @param int $x X coordinate
                 */
                public function onGet(int $x): static
                {
                    $this->body = ['x' => $x];

                    return $this;
                }
            }
            """;

        ExtractInputDtoTextRefactoring.RefactoringResult result = refactoring.refactorResource(
            source,
            source.indexOf("onGet"),
            Set.of("x"),
            "Point",
            "p",
            "MyVendor\\Todo\\Input\\Point"
        );

        assertFalse(result.resourceText().contains("/**\n                 */"));
        assertTrue(result.resourceText().contains("public function onGet(#[Input] Point $p): static"));
    }

    @Test
    void detectsSelectedParameterNamesFromSelectionText() {
        String paramsText = "int $x, int $y, string $label = 'origin'";
        var params = refactoring.parseParams(paramsText);

        assertEquals(Set.of("x"), refactoring.selectedNamesFromText("$this->body = ['x' => $x];", params));
        assertEquals(Set.of("x", "y"), refactoring.selectedNamesFromText("$x + $y", params));
    }

    @Test
    void collapsesSelectedMethodCallArgumentsToInputDto() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            final class Plot
            {
                public function onGet(int $x, int $y): static
                {
                    $this->body = $this->pointQuery->distance($x, $y);

                    return $this;
                }
            }
            """;

        ExtractInputDtoTextRefactoring.RefactoringResult result = refactoring.refactorResource(
            source,
            source.indexOf("onGet"),
            Set.of("x", "y"),
            "PointInput",
            "input",
            "MyVendor\\Todo\\Input\\PointInput"
        );

        assertTrue(result.resourceText().contains("$this->pointQuery->distance($input);"));
        assertFalse(result.resourceText().contains("distance($input->x, $input->y)"));
    }

    @Test
    void keepsFreeFunctionArgumentsAsInputProperties() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            final class Plot
            {
                public function onGet(int $x, int $y): static
                {
                    $this->body = ['max' => max($x, $y)];

                    return $this;
                }
            }
            """;

        ExtractInputDtoTextRefactoring.RefactoringResult result = refactoring.refactorResource(
            source,
            source.indexOf("onGet"),
            Set.of("x", "y"),
            "PointInput",
            "input",
            "MyVendor\\Todo\\Input\\PointInput"
        );

        assertTrue(result.resourceText().contains("max($input->x, $input->y)"));
    }

    @Test
    void refactorsCollapsedQueryInterfaceMethodSignature() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Query;

            use Ray\\MediaQuery\\Annotation\\DbQuery;

            interface PointQueryInterface
            {
                /** @return array{x: int, y: int, squaredDistance: int} */
                #[DbQuery('point_distance', type: 'row')]
                public function distance(int $x, int $y): array;
            }
            """;

        String result = refactoring.refactorQueryInterface(
            source,
            Set.of("distance"),
            Set.of("x", "y"),
            "PointInput",
            "MyVendor\\Todo\\Input\\PointInput"
        );

        assertTrue(result.contains("use MyVendor\\Todo\\Input\\PointInput;"));
        assertFalse(result.contains("use Ray\\InputQuery\\Attribute\\Input;"));
        assertTrue(result.contains("public function distance(PointInput $pointInput): array;"));
        assertFalse(result.contains("public function distance(int $x, int $y): array;"));
    }

    @Test
    void leavesQueryInterfaceUnchangedWhenCollapsedMethodDoesNotMatch() {
        String source = """
            <?php
            namespace MyVendor\\Todo\\Query;

            use Ray\\MediaQuery\\Annotation\\DbQuery;

            interface OtherQueryInterface
            {
                #[DbQuery('other', type: 'row')]
                public function other(int $x, int $y): array;
            }
            """;

        String result = refactoring.refactorQueryInterface(
            source,
            Set.of("distance"),
            Set.of("x", "y"),
            "PointInput",
            "MyVendor\\Todo\\Input\\PointInput"
        );

        assertEquals(source, result);
    }

}
