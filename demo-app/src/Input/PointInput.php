<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Input;

use Ray\InputQuery\Attribute\Input;

final class PointInput
{
    public function __construct(
        #[Input] public readonly int $x = 3,
        #[Input] public readonly int $y = 4,
    ) {
    }
}
