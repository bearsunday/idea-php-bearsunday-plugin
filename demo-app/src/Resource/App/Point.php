<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\ResourceObject;
use MyVendor\MyProject\Query\PointQueryInterface;

final class Point extends ResourceObject
{
    /** @var array{x: int, y: int, squaredDistance: int} */
    public $body;

    public function __construct(
        private readonly PointQueryInterface $pointQuery,
    ) {
    }

    /**
     * Refactoring target: select $x and $y in the dialog and extract them to PointInput.
     */
    public function onGet(int $x = 3, int $y = 4): static
    {
        $this->body = $this->pointQuery->distance($x, $y);

        return $this;
    }
}
