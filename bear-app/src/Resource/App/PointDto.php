<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\ResourceObject;
use MyVendor\MyProject\Input\PointInput;
use MyVendor\MyProject\Query\PointQueryInterface;
use Ray\InputQuery\Attribute\Input;

final class PointDto extends ResourceObject
{
    /** @var array{x: int, y: int, squaredDistance: int} */
    public $body;

    public function __construct(
        private readonly PointQueryInterface $pointQuery,
    ) {
    }

    public function onGet(#[Input] PointInput $point): static
    {
        $this->body = $this->pointQuery->distanceByInput($point);

        return $this;
    }
}
