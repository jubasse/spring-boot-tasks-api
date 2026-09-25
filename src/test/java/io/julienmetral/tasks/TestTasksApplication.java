package io.julienmetral.tasks;

import org.springframework.boot.SpringApplication;

public class TestTasksApplication {

	public static void main(String[] args) {
		SpringApplication.from(TasksApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
