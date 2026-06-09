<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Http;

use BEAR\Dev\Http\HttpResource;
use MyVendor\MyProject\Hypermedia\WorkflowTest as Workflow;

class WorkflowTest extends Workflow
{
    protected function setUp(): void
    {
        $this->resource = new HttpResource('127.0.0.1:8080', __DIR__ . '/index.php', __DIR__ . '/log/workflow.log');
    }
}
