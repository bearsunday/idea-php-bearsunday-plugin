<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\ApiDoc\Annotation\JsonSchema;
use BEAR\Resource\ResourceObject;
use MyVendor\MyProject\Annotation\Audited;

/**
 * Demo target: run "Generate BEAR body type" on this class.
 */
final class BodyTypeDemo extends ResourceObject
{
    #[Audited]
    #[JsonSchema(schema: 'body-type-demo.json')]
    public function onGet(string $name = 'BEAR.Sunday'): static
    {
        $this->body = [
            'id' => 1,
            'name' => $name,
            'posts' => [
                [
                    'id' => 1,
                    'title' => 'Hello',
                ],
                [
                    'id' => 2,
                    'title' => 'Body Type',
                ],
            ],
            'meta' => [
                'active' => true,
                'score' => 12.5,
            ],
        ];

        return $this;
    }

    public function onPost(): static
    {
        $this->body = [
            'status' => 'created',
            'id' => 2,
        ];

        return $this;
    }
}
