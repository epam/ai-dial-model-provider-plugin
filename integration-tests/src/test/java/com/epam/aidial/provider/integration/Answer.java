package com.epam.aidial.provider.integration;

import java.util.List;

/** What the model said: its text, and the names of the tools it called. */
record Answer(String text, List<String> toolCalls) {}
