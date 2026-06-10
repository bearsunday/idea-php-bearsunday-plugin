<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\Page;

use BEAR\Resource\ResourceObject;

final class Dashboard extends ResourceObject
{
    /** @var array{greeting: string} */
    public $body;

    public function onGet(): static
    {
        $this->body = [
            'greeting' => 'Dashboard page',
        ];

        return $this;
    }
}
