<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\Annotation\Embed;
use BEAR\Resource\Annotation\Link;
use BEAR\Resource\ResourceObject;

final class RelationDemoTwo extends ResourceObject
{
    #[Embed(src: 'app://self/point-dto', rel: 'widget')]
    #[Link(href: 'page://self/index', rel: 'top')]
    public function onGet(): static
    {
        $this->body = ['demo' => 'relation-two'];

        return $this;
    }
}
