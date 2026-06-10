<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\ResourceObject;

/**
 * @psalm-type UserGetBody = array{id: int, name: string}
 * @property UserGetBody|null $body
 */
final class User extends ResourceObject
{
    public function onGet(int $id = 1): static
    {
        $this->body = [
            'id' => $id,
            'name' => 'BEAR',
        ];

        return $this;
    }
}
